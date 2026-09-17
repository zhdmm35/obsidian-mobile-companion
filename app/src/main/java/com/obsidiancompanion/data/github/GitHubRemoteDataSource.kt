package com.obsidiancompanion.data.github

import com.obsidiancompanion.model.DomainError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GitHub 远端数据源（§37）：validateToken / listRepositories / getRepository / getTree /
 * getRawFile / getFileContent / updateFile（Phase 5 write）。
 * 职责：HTTP 调用 + 状态码映射 + truncated fallback；DTO 不外泄（只返回 domain 模型）。
 */
class GitHubRemoteDataSource(
    private val api: GitHubApi,
    private val baseUrl: okhttp3.HttpUrl,
    private val tokenProvider: () -> String? = { null },
) {

    suspend fun validateToken(): GitHubResult<GithubUser> = runGuarded {
        if (tokenProvider() == null) return@runGuarded GitHubResult.Fail(DomainError.Unauthorized)
        val resp = api.getUser()
        val body = resp.body()
        when {
            resp.isSuccessful && body != null -> GitHubResult.Ok(GithubUser(body.login))
            else -> resp.toFail()
        }
    }

    /** 分页拉取可访问仓库（最多 10 页，实际 <100 仓库时一次即止）。 */
    suspend fun listRepositories(): GitHubResult<List<GithubRepo>> = runGuarded {
        val all = mutableListOf<RepoDto>()
        var page = 1
        while (page <= 10) {
            val resp = api.listRepos(perPage = 100, page = page, sort = "updated")
            val body = resp.body()
            when {
                resp.isSuccessful && body != null -> {
                    all += body
                    if (body.size < 100) break
                    page++
                }
                else -> return@runGuarded resp.toFail()
            }
        }
        GitHubResult.Ok(all.map { it.toDomain() })
    }

    suspend fun getRepository(owner: String, repo: String): GitHubResult<GithubRepo> = runGuarded {
        val resp = api.getRepo(owner, repo)
        val body = resp.body()
        when {
            resp.isSuccessful && body != null -> GitHubResult.Ok(body.toDomain())
            else -> resp.toFail()
        }
    }

    /**
     * 递归 Tree + ETag 条件请求（§19）。
     * truncated == true 时绝不假装数据完整（§16）：改用非递归逐层 BFS fallback。
     */
    suspend fun getTree(
        owner: String,
        repo: String,
        branch: String,
        etag: String?,
    ): GitHubResult<RemoteTree> = runGuarded {
        val resp = api.getTree(owner, repo, branch, recursive = 1, ifNoneMatch = etag)
        val newEtag = resp.headers()["ETag"] ?: etag
        when {
            resp.code() == 304 -> GitHubResult.NotModified(newEtag)

            !resp.isSuccessful -> resp.toFail()

            else -> {
                val body = resp.body()
                    ?: return@runGuarded GitHubResult.Fail(DomainError.MalformedResponse)
                if (!body.truncated) {
                    GitHubResult.Ok(
                        RemoteTree(body.sha, body.tree.map { it.toDomain() }, newEtag),
                        newEtag,
                    )
                } else {
                    fetchTreeByLayers(owner, repo, body.sha, newEtag)
                }
            }
        }
    }

    /** Raw 正文（Markdown 源文本字节）。路径按段编码（空格→%20，'/' 保留为分隔符）。 */
    suspend fun getRawFile(owner: String, repo: String, path: String): GitHubResult<ByteArray> =
        withContext(Dispatchers.IO) {
            runGuarded {
                val resp = api.getRawContent(contentsUrl(owner, repo, path))
                when {
                    resp.isSuccessful -> {
                        val bytes = resp.body()?.bytes()
                        if (bytes != null) {
                            GitHubResult.Ok(bytes)
                        } else {
                            GitHubResult.Fail(DomainError.MalformedResponse)
                        }
                    }
                    else -> resp.toFail()
                }
            }
        }

    /** JSON 形态 Contents read：远端最新 sha + Base64 正文（冲突流程用，§16-18）。 */
    suspend fun getFileContent(owner: String, repo: String, path: String): GitHubResult<RemoteFileContent> =
        withContext(Dispatchers.IO) {
            runGuarded {
                val resp = api.getContent(contentsUrl(owner, repo, path))
                val body = resp.body()
                when {
                    resp.isSuccessful && body != null -> {
                        val encoded = body.content
                            ?: return@runGuarded GitHubResult.Fail(DomainError.MalformedResponse)
                        // GitHub Base64 每 60 字符换行 —— MIME decoder 忽略非法字符
                        val bytes = try {
                            java.util.Base64.getMimeDecoder().decode(encoded)
                        } catch (e: IllegalArgumentException) {
                            return@runGuarded GitHubResult.Fail(DomainError.MalformedResponse)
                        }
                        GitHubResult.Ok(RemoteFileContent(body.sha, bytes))
                    }
                    else -> resp.toFail()
                }
            }
        }

    /**
     * Contents write（Phase 5 §7）：更新已有 Markdown。
     * content 为 UTF-8 字节（中文/emoji 直接 Base64，不经过任何转码 —— §43）；
     * sha 为编辑 session 冻结的 base blob SHA，过期时 GitHub 返回 409 → DomainError.Conflict（§15）。
     */
    suspend fun updateFile(
        owner: String,
        repo: String,
        path: String,
        content: ByteArray,
        sha: String,
        message: String,
        branch: String,
    ): GitHubResult<UpdatedFile> = withContext(Dispatchers.IO) {
        runGuarded {
            val request = UpdateFileRequestDto(
                message = message,
                content = java.util.Base64.getEncoder().encodeToString(content),
                sha = sha,
                branch = branch,
            )
            val resp = api.putContent(contentsUrl(owner, repo, path), request)
            val body = resp.body()
            when {
                resp.isSuccessful && body != null -> {
                    val newSha = body.content?.sha
                        ?: return@runGuarded GitHubResult.Fail(DomainError.MalformedResponse)
                    GitHubResult.Ok(UpdatedFile(newSha = newSha, commitSha = body.commit?.sha))
                }
                else -> resp.toFail()
            }
        }
    }

    /**
     * Contents create：PUT 不带 sha → 仅创建新文件（新建笔记 / 快速收集）。
     * 路径已存在时 GitHub 返回 422 —— 映射为 DomainError.Conflict（「已存在」语义，
     * 与编辑的 409「被改过了」同属「不能静默继续」，由调用方各自解释）。
     */
    suspend fun createFile(
        owner: String,
        repo: String,
        path: String,
        content: ByteArray,
        message: String,
        branch: String,
    ): GitHubResult<UpdatedFile> = withContext(Dispatchers.IO) {
        runGuarded {
            val request = CreateFileRequestDto(
                message = message,
                content = java.util.Base64.getEncoder().encodeToString(content),
                branch = branch,
            )
            val resp = api.createContent(contentsUrl(owner, repo, path), request)
            val body = resp.body()
            when {
                resp.isSuccessful && body != null -> {
                    val newSha = body.content?.sha
                        ?: return@runGuarded GitHubResult.Fail(DomainError.MalformedResponse)
                    GitHubResult.Ok(UpdatedFile(newSha = newSha, commitSha = body.commit?.sha))
                }
                resp.code() == 422 -> GitHubResult.Fail(DomainError.Conflict, resp.code())
                else -> resp.toFail()
            }
        }
    }

    /** repos/{o}/{r}/contents/{path…}：路径按段编码（空格→%20，CJK→UTF-8 百分号，'/' 保留为分隔符）。 */
    private fun contentsUrl(owner: String, repo: String, path: String): okhttp3.HttpUrl =
        baseUrl.newBuilder()
            .addPathSegments("repos")
            .addPathSegment(owner)
            .addPathSegment(repo)
            .addPathSegments("contents")
            .addPathSegments(path)
            .build()

    /** 非递归逐层展开（truncated fallback）：从 root tree 开始按子树 SHA 逐层获取。 */
    private suspend fun fetchTreeByLayers(
        owner: String,
        repo: String,
        rootTreeSha: String,
        etag: String?,
    ): GitHubResult<RemoteTree> {
        val entries = mutableListOf<RemoteTreeEntry>()
        val pending = ArrayDeque<Pair<String, String>>() // (fullPath, treeSha)
        pending += "" to rootTreeSha

        while (pending.isNotEmpty() && entries.size < MAX_ENTRIES) {
            val (prefix, treeSha) = pending.removeFirst()
            val resp = api.getTree(owner, repo, treeSha, recursive = null, ifNoneMatch = null)
            val body = resp.body()
            when {
                !resp.isSuccessful -> return GitHubResult.Fail(DomainError.ServerError, resp.code())
                body == null -> return GitHubResult.Fail(DomainError.MalformedResponse)
                body.truncated -> return GitHubResult.Fail(DomainError.MalformedResponse) // 单层也截断，属于异常仓库
                else -> body.tree.forEach { dto ->
                    val fullPath = if (prefix.isEmpty()) dto.path else "$prefix/${dto.path}"
                    entries += dto.toDomain().let { it.copy(path = fullPath) }
                    if (dto.type == "tree") pending += fullPath to dto.sha
                }
            }
        }
        return GitHubResult.Ok(RemoteTree(rootTreeSha, entries, etag), etag)
    }

    private companion object {
        const val MAX_ENTRIES = 100_000
    }
}
