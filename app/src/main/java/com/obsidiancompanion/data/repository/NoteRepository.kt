package com.obsidiancompanion.data.repository

import com.obsidiancompanion.data.cache.ContentCache
import com.obsidiancompanion.data.github.GitHubRemoteDataSource
import com.obsidiancompanion.data.github.GitHubResult
import com.obsidiancompanion.data.markdown.MarkdownParser
import com.obsidiancompanion.data.metadata.AppDatabase
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.PendingEditEntity
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.data.settings.SettingsRepository
import com.obsidiancompanion.model.DomainError
import com.obsidiancompanion.model.markdown.MdDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/** openNote 的结果（§49 语义）。 */
sealed interface NoteOpenResult {

    /** 正文就绪：fromCache=true 表示秒开缓存；false 表示刚从 GitHub 拉取并已写缓存。 */
    data class Content(
        val path: String,
        val markdown: String,
        val document: MdDocument,
        val fromCache: Boolean,
        val entry: RepoEntryEntity,
        val fetchedAt: Long,
    ) : NoteOpenResult

    /** 离线且从未缓存（§5：专用提示，不是 Generic Error）。 */
    data class OfflineNotCached(val entry: RepoEntryEntity) : NoteOpenResult

    data class Error(val error: DomainError, val entry: RepoEntryEntity?) : NoteOpenResult
}

/** Phase 5 §14-§16：保存结果。任何失败都保留 draft（§21），成功才清除。 */
sealed interface NoteSaveResult {

    /** PUT 成功：newSha 已写缓存 + Tree entry 已指向新 blob。 */
    data class Saved(val path: String, val newSha: String, val commitSha: String?) : NoteSaveResult

    /** base SHA 过期（远端在编辑期间被修改）—— 绝不静默覆盖；draft 已保留，进入 ConflictScreen。 */
    data class Conflict(val path: String) : NoteSaveResult

    data class Error(val error: DomainError) : NoteSaveResult
}

/**
 * NoteRepository（§39）：openNote(path) = Index 查 blobSha → ContentCache →（MISS）GitHub → 缓存。
 * ViewModel 不感知 Cache 命中与否；不做 File IO；解析在 Default 线程池（§79）。
 */
class NoteRepository(
    private val index: RepositoryIndexRepository,
    private val remote: GitHubRemoteDataSource,
    private val cache: ContentCache,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
) {

    suspend fun openNote(path: String): NoteOpenResult = withContext(Dispatchers.Default) {
        val s = settings.flow.firstOrNull() ?: return@withContext NoteOpenResult.Error(DomainError.Unknown, null)
        val repoId = s.repoId ?: return@withContext NoteOpenResult.Error(DomainError.Unknown, null)
        val entry = index.getEntry(repoId, path)
            ?: return@withContext NoteOpenResult.Error(DomainError.NotFound, null)
        if (entry.kind != EntryKind.MARKDOWN) {
            return@withContext NoteOpenResult.Error(DomainError.NotFound, entry)
        }

        val cached = cache.get(entry.blobSha)
        if (cached != null) {
            return@withContext content(path, cached, fromCache = true, entry)
        }

        when (val r = remote.getRawFile(s.owner!!, s.repo!!, path)) {
            is GitHubResult.Ok -> {
                cache.put(entry.blobSha, r.value)
                content(path, r.value, fromCache = false, entry)
            }
            is GitHubResult.NotModified -> NoteOpenResult.Error(DomainError.Unknown, entry) // 内容请求不发 ETag，不应出现

            is GitHubResult.Fail -> when (r.error) {
                DomainError.NetworkUnavailable -> NoteOpenResult.OfflineNotCached(entry)
                else -> NoteOpenResult.Error(r.error, entry)
            }
        }
    }

    private fun content(path: String, bytes: ByteArray, fromCache: Boolean, entry: RepoEntryEntity): NoteOpenResult.Content {
        val text = String(bytes, Charsets.UTF_8)
        return NoteOpenResult.Content(
            path = path,
            markdown = text,
            document = MarkdownParser.parse(text),
            fromCache = fromCache,
            entry = entry,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /** §51：内容真正展示给用户后才计入阅读（网络失败且无 cache 不算）。 */
    suspend fun markRead(path: String) {
        val s = settings.flow.firstOrNull() ?: return
        val repoId = s.repoId ?: return
        db.noteMetadataDao().markRead(repoId, path, System.currentTimeMillis())
    }

    /** §52：收藏纯属 App 本地 metadata —— 不写 GitHub、不写 Markdown、不上传。 */
    suspend fun setFavorite(path: String, favorite: Boolean) {
        val s = settings.flow.firstOrNull() ?: return
        val repoId = s.repoId ?: return
        db.noteMetadataDao().setFavorite(repoId, path, favorite)
    }

    /* ── Phase 5：Editing + GitHub Write（§2-§18）────────────────────── */
    /** 冲突页取远端最新版（§16）：domain 化返回，ViewModel 不接触 GitHubResult。 */
    sealed interface RemoteNoteVersion {
        data class Ready(val sha: String, val content: String) : RemoteNoteVersion
        data class Error(val error: DomainError) : RemoteNoteVersion
    }

    /** 写路径共用上下文：owner/repo/branch 任一缺失 = 未完成 onboarding，不可写。 */
    private data class WriteContext(
        val repoId: String,
        val owner: String,
        val repo: String,
        val branch: String,
    )

    /** repoId 与 AppSettings.repoId 同式（owner/repo 均非空才可写）。 */
    private suspend fun writeContext(): WriteContext? {
        val s = settings.flow.firstOrNull() ?: return null
        val owner = s.owner ?: return null
        val repo = s.repo ?: return null
        val branch = s.defaultBranch ?: return null
        return WriteContext(repoId = "$owner/$repo", owner = owner, repo = repo, branch = branch)
    }

    /**
     * 保存已有 Markdown（§3/§6）：draft 暂存 → PUT(base sha) → 成功收敛 / 冲突与失败保留 draft。
     * baseSha 为编辑 session 进入时冻结的 blob SHA（§39），不是进入 Editor 后再查的「当前」值。
     */
    suspend fun saveNote(path: String, baseSha: String, content: String): NoteSaveResult =
        withContext(Dispatchers.Default) {
            val ctx = writeContext() ?: return@withContext NoteSaveResult.Error(DomainError.Unknown)
            val entry = index.getEntry(ctx.repoId, path)
                ?: return@withContext NoteSaveResult.Error(DomainError.NotFound)
            if (entry.kind != EntryKind.MARKDOWN) return@withContext NoteSaveResult.Error(DomainError.NotFound)

            // §20：真正发送 GitHub Write 前先暂存（写入失败 / 冲突 / 保存过程中崩溃均可恢复）
            stageDraft(ctx.repoId, path, baseSha, content)

            putAndConverge(ctx, path, content, baseSha)
        }

    /**
     * 新建 Markdown 笔记（新建对话框 / 分享快速收集）：PUT 不带 sha 的「仅创建」。
     * Tree 已有该路径 → 直接 Conflict（不发请求）；远端 422（竞态已存在）同样归一为 Conflict ——
     * 创建场景的 Conflict 只表示「同名已存在」，由调用方提示换名，绝不进编辑冲突流程。
     * 成功后本地立即收敛：正文写 ContentCache + Tree 插入新 entry（Reader/Editor 即刻可开），
     * 整树由调用方异步 refreshTree 补齐（如新目录的 DIRECTORY 行）。
     */
    suspend fun createNote(path: String, content: String): NoteSaveResult =
        withContext(Dispatchers.Default) {
            val ctx = writeContext() ?: return@withContext NoteSaveResult.Error(DomainError.Unknown)
            if (index.getEntry(ctx.repoId, path) != null) {
                return@withContext NoteSaveResult.Conflict(path)
            }

            val bytes = content.toByteArray(Charsets.UTF_8)
            val message = "mobile: create ${path.substringAfterLast('/')}"
            when (val r = remote.createFile(ctx.owner, ctx.repo, path, bytes, message, ctx.branch)) {
                is GitHubResult.Ok -> {
                    cache.put(r.value.newSha, bytes)
                    db.repoEntryDao().insertAll(
                        listOf(
                            RepoEntryEntity(
                                repoId = ctx.repoId,
                                path = path,
                                name = path.substringAfterLast('/'),
                                parentPath = path.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() },
                                kind = EntryKind.MARKDOWN,
                                blobSha = r.value.newSha,
                                size = bytes.size.toLong(),
                                observedChangedAt = System.currentTimeMillis(),
                            ),
                        ),
                    )
                    NoteSaveResult.Saved(path, r.value.newSha, r.value.commitSha)
                }
                is GitHubResult.Fail -> when (r.error) {
                    DomainError.Conflict -> NoteSaveResult.Conflict(path)
                    else -> NoteSaveResult.Error(r.error)
                }
                is GitHubResult.NotModified -> NoteSaveResult.Error(DomainError.Unknown) // PUT 不会 304；防御分支
            }
        }

    /** §18「使用我的修改」：重新获取最新 remote SHA（§45 Use Mine）→ 再用我的正文 PUT —— 用户明确确认的覆盖。 */
    suspend fun resolveConflictUseMine(path: String, myContent: String): NoteSaveResult =
        withContext(Dispatchers.Default) {
            val ctx = writeContext() ?: return@withContext NoteSaveResult.Error(DomainError.Unknown)
            when (val latest = remote.getFileContent(ctx.owner, ctx.repo, path)) {
                is GitHubResult.Ok -> putAndConverge(ctx, path, myContent, latest.value.sha)
                is GitHubResult.Fail -> NoteSaveResult.Error(latest.error)
                is GitHubResult.NotModified -> NoteSaveResult.Error(DomainError.Unknown)
            }
        }

    /** §18「使用 GitHub 版本」：放弃手机修改 —— 远端正文写缓存 + entry 指向远端 SHA + 清 draft。 */
    suspend fun resolveConflictUseRemote(path: String): NoteSaveResult =
        withContext(Dispatchers.Default) {
            val ctx = writeContext() ?: return@withContext NoteSaveResult.Error(DomainError.Unknown)
            when (val latest = remote.getFileContent(ctx.owner, ctx.repo, path)) {
                is GitHubResult.Ok -> {
                    applySavedContent(ctx.repoId, path, latest.value.sha, latest.value.bytes)
                    db.pendingEditDao().delete(ctx.repoId, path)
                    NoteSaveResult.Saved(path, latest.value.sha, commitSha = null)
                }
                is GitHubResult.Fail -> NoteSaveResult.Error(latest.error)
                is GitHubResult.NotModified -> NoteSaveResult.Error(DomainError.Unknown)
            }
        }

    /** Editor 进入时的崩溃/失败恢复检查（§21）。 */
    suspend fun getPendingEdit(path: String): PendingEditEntity? {
        val repoId = settings.flow.firstOrNull()?.repoId ?: return null
        return db.pendingEditDao().get(repoId, path)
    }

    /** 冲突页展示用：远端最新版本（sha + 正文，§16）。 */
    suspend fun fetchRemoteVersion(path: String): RemoteNoteVersion {
        val ctx = writeContext() ?: return RemoteNoteVersion.Error(DomainError.Unknown)
        return when (val r = remote.getFileContent(ctx.owner, ctx.repo, path)) {
            is GitHubResult.Ok -> RemoteNoteVersion.Ready(r.value.sha, String(r.value.bytes, Charsets.UTF_8))
            is GitHubResult.Fail -> RemoteNoteVersion.Error(r.error)
            is GitHubResult.NotModified -> RemoteNoteVersion.Error(DomainError.Unknown)
        }
    }

    suspend fun clearPendingEdit(path: String) {
        val repoId = settings.flow.firstOrNull()?.repoId ?: return
        db.pendingEditDao().delete(repoId, path)
    }

    /** PUT → 成功：缓存新 SHA + entry 指向新 blob + 清 draft；409 → Conflict（draft 保留）；其余 → Error（draft 保留）。 */
    private suspend fun putAndConverge(
        ctx: WriteContext,
        path: String,
        content: String,
        sha: String,
    ): NoteSaveResult {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val message = "mobile: update ${path.substringAfterLast('/')}" // §8：简单 commit message
        return when (val r = remote.updateFile(ctx.owner, ctx.repo, path, bytes, sha, message, ctx.branch)) {
            is GitHubResult.Ok -> {
                applySavedContent(ctx.repoId, path, r.value.newSha, bytes)
                db.pendingEditDao().delete(ctx.repoId, path)
                NoteSaveResult.Saved(path, r.value.newSha, r.value.commitSha)
            }
            is GitHubResult.Fail -> when (r.error) {
                DomainError.Conflict -> NoteSaveResult.Conflict(path) // §15：绝不自动覆盖
                else -> NoteSaveResult.Error(r.error)
            }
            is GitHubResult.NotModified -> NoteSaveResult.Error(DomainError.Unknown) // PUT 不会 304；防御分支
        }
    }

    /**
     * 保存成功后的本地收敛（§10/§11/§48/§50）：
     * 新正文立即按 newSha 写 ContentCache（Reader 返回直接读缓存，不重新下载自己刚上传的正文），
     * Tree entry 立即指向 newSha 且 observedChangedAt = now（Home 最近修改即时出现）。
     */
    private suspend fun applySavedContent(repoId: String, path: String, newSha: String, bytes: ByteArray) {
        cache.put(newSha, bytes)
        db.repoEntryDao().updateBlobAfterSave(repoId, path, newSha, bytes.size.toLong(), System.currentTimeMillis())
    }

    private suspend fun stageDraft(repoId: String, path: String, baseSha: String, content: String) {
        db.pendingEditDao().upsert(
            PendingEditEntity(repoId, path, baseSha, content, System.currentTimeMillis()),
        )
    }
}
