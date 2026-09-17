package com.obsidiancompanion.data.github

import com.obsidiancompanion.model.DomainError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** §71：GitHub Remote 用 MockWebServer 覆盖 正常/401/403/404/304/429/500/畸形响应/断连/截断 fallback。 */
class GitHubRemoteDataSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var remote: GitHubRemoteDataSource
    private var token: String? = "gh_test_token"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        remote = GitHubRemoteDataSource(
            api = GitHubApiFactory.create(server.url("/").toString()) { token },
            baseUrl = server.url("/"),
            tokenProvider = { token },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String): MockResponse =
        MockResponse().addHeader("Content-Type: " +
            "application/json").setBody(body)

    /* ── validateToken ─────────────────────────────────────── */

    @Test
    fun validateToken_ok() = runTest {
        server.enqueue(json("""{"login":"zhangsan"}"""))
        val r = remote.validateToken()
        assertTrue(r is GitHubResult.Ok)
        assertEquals("zhangsan", (r as GitHubResult.Ok).value.login)
        val req = server.takeRequest()
        assertEquals("Bearer gh_test_token", req.getHeader("Authorization"))
        assertEquals("/user", req.path)
    }

    @Test
    fun validateToken_401() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(GitHubResult.Fail(DomainError.Unauthorized, 401), remote.validateToken())
    }

    @Test
    fun validateToken_missingToken() = runTest {
        token = null
        assertEquals(GitHubResult.Fail(DomainError.Unauthorized, null), remote.validateToken())
    }

    /* ── listRepositories ──────────────────────────────────── */

    @Test
    fun listRepositories_ok() = runTest {
        server.enqueue(
            json(
                """[{"id":1,"name":"My-Obsidian-Vault","owner":{"login":"zhangsan"},"private":true,"default_branch":"master"},""" +
                    """{"id":2,"name":"Notes","owner":{"login":"zhangsan"},"private":false,"default_branch":"main"}]""",
            ),
        )
        val r = remote.listRepositories()
        assertTrue(r is GitHubResult.Ok)
        val repos = (r as GitHubResult.Ok).value
        assertEquals(2, repos.size)
        assertEquals("My-Obsidian-Vault", repos[0].name)
        assertTrue(repos[0].isPrivate)
        assertEquals("master", repos[0].defaultBranch)
    }

    @Test
    fun listRepositories_malformedJson() = runTest {
        server.enqueue(json("""{"broken": ["""))
        assertEquals(
            GitHubResult.Fail(DomainError.MalformedResponse),
            remote.listRepositories(),
        )
    }

    /* ── getRepository（default_branch 真实读取，不假设 main）──── */

    @Test
    fun getRepository_defaultBranchHonored() = runTest {
        server.enqueue(
            json(
                """{"id":1,"name":"vault","owner":{"login":"zhangsan"},"private":true,"default_branch":"develop"}""",
            ),
        )
        val r = remote.getRepository("zhangsan", "vault")
        assertTrue(r is GitHubResult.Ok)
        assertEquals("develop", (r as GitHubResult.Ok).value.defaultBranch)
    }

    @Test
    fun getRepository_404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            GitHubResult.Fail(DomainError.NotFound, 404),
            remote.getRepository("zhangsan", "nope"),
        )
    }

    /* ── getTree：ETag / 304 / truncated fallback / 限流 ────── */

    @Test
    fun getTree_okWithEtagSentAndCaptured() = runTest {
        server.enqueue(
            json("""{"sha":"r1","truncated":false,"tree":[{"path":"A.md","type":"blob","sha":"a1","size":3}]}""")
                .addHeader("ETag", "\"tree-etag-1\""),
        )
        val r = remote.getTree("o", "r", "master", etag = "\"tree-etag-0\"")
        assertTrue(r is GitHubResult.Ok)
        val tree = (r as GitHubResult.Ok).value
        assertEquals("r1", tree.rootSha)
        assertEquals(1, tree.entries.size)
        assertEquals("\"tree-etag-1\"", tree.etag)
        val req = server.takeRequest()
        assertEquals("\"tree-etag-0\"", req.getHeader("If-None-Match"))
        assertTrue(req.path!!.contains("recursive=1"))
    }

    @Test
    fun getTree_304NotModified() = runTest {
        server.enqueue(MockResponse().setResponseCode(304).addHeader("ETag", "\"tree-etag-1\""))
        val r = remote.getTree("o", "r", "master", etag = "\"tree-etag-1\"")
        assertTrue(r is GitHubResult.NotModified)
        assertEquals("\"tree-etag-1\"", (r as GitHubResult.NotModified).etag)
    }

    @Test
    fun getTree_truncatedFallsBackToLayeredFetch() = runTest {
        // 1) recursive 响应被截断
        server.enqueue(json("""{"sha":"rootsha","truncated":true,"tree":[{"path":"A.md","type":"blob","sha":"a","size":10}]}"""))
        // 2) root 非递归：一个 blob + 一个子树
        server.enqueue(
            json(
                """{"sha":"rootsha","truncated":false,"tree":[""" +
                    """{"path":"A.md","type":"blob","sha":"a","size":10},""" +
                    """{"path":"dir","type":"tree","sha":"d1"}]}""",
            ),
        )
        // 3) 子树 d1 非递归：一个 blob
        server.enqueue(
            json("""{"sha":"d1","truncated":false,"tree":[{"path":"B.md","type":"blob","sha":"b","size":5}]}"""),
        )
        val r = remote.getTree("o", "r", "master", etag = null)
        assertTrue(r is GitHubResult.Ok)
        val entries = (r as GitHubResult.Ok).value.entries
        assertEquals(listOf("A.md", "dir", "dir/B.md"), entries.map { it.path })
        assertTrue(entries.first { it.path == "dir" }.isDirectory)
    }

    @Test
    fun getTree_403RateLimited() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).addHeader("X-RateLimit-Remaining", "0"))
        assertEquals(
            GitHubResult.Fail(DomainError.RateLimited, 403),
            remote.getTree("o", "r", "master", null),
        )
    }

    @Test
    fun getTree_403Forbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(
            GitHubResult.Fail(DomainError.Forbidden, 403),
            remote.getTree("o", "r", "master", null),
        )
    }

    @Test
    fun getTree_429RateLimited() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(
            GitHubResult.Fail(DomainError.RateLimited, 429),
            remote.getTree("o", "r", "master", null),
        )
    }

    @Test
    fun getTree_500ServerError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(
            GitHubResult.Fail(DomainError.ServerError, 500),
            remote.getTree("o", "r", "master", null),
        )
    }

    /* ── getRawFile ────────────────────────────────────────── */

    @Test
    fun getRawFile_ok() = runTest {
        server.enqueue(MockResponse().setBody("# Hello 世界"))
        val r = remote.getRawFile("o", "r", "工作/番禺.md")
        assertTrue(r is GitHubResult.Ok)
        assertEquals("# Hello 世界", String((r as GitHubResult.Ok).value))
        val req = server.takeRequest()
        // 路径含空格/CJK 时正确 URL 编码
        assertTrue(req.path!!.contains("%E7%95%AA%E7%A6%BA.md"))
    }

    @Test
    fun getRawFile_pathWithSpaceEncoded() = runTest {
        server.enqueue(MockResponse().setBody("x"))
        val r = remote.getRawFile("o", "r", "Java/Spring Boot.md")
        assertTrue(r is GitHubResult.Ok)
        val req = server.takeRequest()
        assertEquals("/repos/o/r/contents/Java/Spring%20Boot.md", req.path)
    }

    @Test
    fun getRawFile_404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            GitHubResult.Fail(DomainError.NotFound, 404),
            remote.getRawFile("o", "r", "missing.md"),
        )
    }

    @Test
    fun networkError_mapsToNetworkUnavailable() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(
            GitHubResult.Fail(DomainError.NetworkUnavailable),
            remote.getRawFile("o", "r", "A.md"),
        )
    }

    /* ── updateFile（Phase 5 §41-§43）────────────────────────── */

    @Test
    fun updateFile_ok_returnsNewShaAndSendsFullBody() = runTest {
        val markdown = "# 标题\n\n中文正文 🎉 [[Spring Boot]]\n\n> [!note] 提示\n> 内容\n"
        server.enqueue(
            json(
                """{"content":{"name":"A.md","path":"dir/A.md","sha":"def456"},""" +
                    """"commit":{"sha":"commit999"}}""",
            ),
        )
        val r = remote.updateFile(
            owner = "o", repo = "r", path = "dir/A.md",
            content = markdown.toByteArray(Charsets.UTF_8),
            sha = "abc123", message = "mobile: update A.md", branch = "master",
        )
        assertTrue(r is GitHubResult.Ok)
        val updated = (r as GitHubResult.Ok).value
        assertEquals("def456", updated.newSha)
        assertEquals("commit999", updated.commitSha)

        // §42：PUT body 必须包含 message / base64 content / sha / branch
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/repos/o/r/contents/dir/A.md", req.path)
        val body = req.body.readUtf8()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals("mobile: update A.md", json["message"]!!.jsonPrimitive.content)
        assertEquals("abc123", json["sha"]!!.jsonPrimitive.content)
        assertEquals("master", json["branch"]!!.jsonPrimitive.content)
        // §43：Base64 解码后与原始 UTF-8 字节完全一致（中文 / emoji / WikiLink / Callout）
        val decoded = java.util.Base64.getDecoder().decode(json["content"]!!.jsonPrimitive.content)
        assertEquals(markdown, String(decoded, Charsets.UTF_8))
    }

    @Test
    fun updateFile_pathWithSpaceAndCjkEncoded() = runTest {
        server.enqueue(json("""{"content":{"sha":"s"},"commit":{"sha":"c"}}"""))
        val r = remote.updateFile("o", "r", "Java/Spring 笔记.md", "x".toByteArray(), "abc", "m", "main")
        assertTrue(r is GitHubResult.Ok)
        val req = server.takeRequest()
        assertEquals("/repos/o/r/contents/Java/Spring%20%E7%AC%94%E8%AE%B0.md", req.path)
    }

    @Test
    fun updateFile_401() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(
            GitHubResult.Fail(DomainError.Unauthorized, 401),
            remote.updateFile("o", "r", "A.md", "x".toByteArray(), "abc", "m", "main"),
        )
    }

    @Test
    fun updateFile_403() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(
            GitHubResult.Fail(DomainError.Forbidden, 403),
            remote.updateFile("o", "r", "A.md", "x".toByteArray(), "abc", "m", "main"),
        )
    }

    @Test
    fun updateFile_404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            GitHubResult.Fail(DomainError.NotFound, 404),
            remote.updateFile("o", "r", "A.md", "x".toByteArray(), "abc", "m", "main"),
        )
    }

    /** §15/§41：stale SHA → 409 → Conflict，绝不静默覆盖。 */
    @Test
    fun updateFile_409_mapsToConflict() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"is at def but expected abc"}"""))
        assertEquals(
            GitHubResult.Fail(DomainError.Conflict, 409),
            remote.updateFile("o", "r", "A.md", "x".toByteArray(), "abc", "m", "main"),
        )
    }

    @Test
    fun updateFile_networkError() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(
            GitHubResult.Fail(DomainError.NetworkUnavailable),
            remote.updateFile("o", "r", "A.md", "x".toByteArray(), "abc", "m", "main"),
        )
    }

    /* ── getFileContent（冲突流程：一次拿最新 sha + 正文）──────── */

    @Test
    fun getFileContent_ok_decodesMimeBase64() = runTest {
        val text = "远端版本 🚀 中文"
        // GitHub 每 60 字符换行 —— 手动构造带换行的 Base64
        val b64 = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
            .chunked(8).joinToString("\n")
        server.enqueue(json("""{"name":"A.md","path":"A.md","sha":"def456","encoding":"base64","content":"$b64"}"""))
        val r = remote.getFileContent("o", "r", "A.md")
        assertTrue(r is GitHubResult.Ok)
        val file = (r as GitHubResult.Ok).value
        assertEquals("def456", file.sha)
        assertEquals(text, String(file.bytes, Charsets.UTF_8))
    }

    @Test
    fun getFileContent_404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            GitHubResult.Fail(DomainError.NotFound, 404),
            remote.getFileContent("o", "r", "gone.md"),
        )
    }

    @Test
    fun getFileContent_badBase64_malformed() = runTest {
        // 单个字符不是合法 Base64 单元（长度 % 4 == 1）→ decode 必抛 → MalformedResponse
        server.enqueue(json("""{"sha":"s","content":"a"}"""))
        assertEquals(
            GitHubResult.Fail(DomainError.MalformedResponse),
            remote.getFileContent("o", "r", "A.md"),
        )
    }

    @Test
    fun getFileContent_missingContent_malformed() = runTest {
        server.enqueue(json("""{"sha":"s"}"""))
        assertEquals(
            GitHubResult.Fail(DomainError.MalformedResponse),
            remote.getFileContent("o", "r", "A.md"),
        )
    }

    /* ── createFile（新建笔记 / 快速收集：PUT 不带 sha）────────────────── */

    @Test
    fun createFile_ok_sendsBodyWithoutSha() = runTest {
        val markdown = "分享的文本 https://example.com\n"
        server.enqueue(json("""{"content":{"name":"N.md","path":"Inbox/N.md","sha":"newsha1"},"commit":{"sha":"c1"}}"""))
        val r = remote.createFile(
            owner = "o", repo = "r", path = "Inbox/N.md",
            content = markdown.toByteArray(Charsets.UTF_8),
            message = "mobile: create N.md", branch = "main",
        )
        assertTrue(r is GitHubResult.Ok)
        assertEquals("newsha1", (r as GitHubResult.Ok).value.newSha)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/repos/o/r/contents/Inbox/N.md", req.path)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        // 仅创建语义的关键：body 绝不能带 sha 字段（带了就变成「更新」）
        assertTrue("sha" !in body)
        assertEquals("mobile: create N.md", body["message"]!!.jsonPrimitive.content)
        assertEquals("main", body["branch"]!!.jsonPrimitive.content)
        val decoded = java.util.Base64.getDecoder().decode(body["content"]!!.jsonPrimitive.content)
        assertEquals(markdown, String(decoded, Charsets.UTF_8))
    }

    /** 路径已存在 → GitHub 422 → 归一为 Conflict（「已存在」，由调用方提示换名）。 */
    @Test
    fun createFile_422_mapsToConflict() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Invalid request"}"""))
        assertEquals(
            GitHubResult.Fail(DomainError.Conflict, 422),
            remote.createFile("o", "r", "A.md", "x".toByteArray(), "m", "main"),
        )
    }

    @Test
    fun createFile_403() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(
            GitHubResult.Fail(DomainError.Forbidden, 403),
            remote.createFile("o", "r", "A.md", "x".toByteArray(), "m", "main"),
        )
    }
}
