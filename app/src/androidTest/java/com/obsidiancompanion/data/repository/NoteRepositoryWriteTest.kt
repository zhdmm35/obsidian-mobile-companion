package com.obsidiancompanion.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obsidiancompanion.data.cache.ContentCache
import com.obsidiancompanion.data.github.GitHubApiFactory
import com.obsidiancompanion.data.github.GitHubRemoteDataSource
import com.obsidiancompanion.data.metadata.AppDatabase
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.data.settings.SettingsRepository
import com.obsidiancompanion.model.DomainError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 5 §41-§46 仓库级写流程测试：
 * MockWebServer（PUT/GET contents）+ in-memory Room + 真实 ContentCache（临时目录）+ 真实 SettingsRepository（测试包隔离 DataStore）。
 * 覆盖：save success / conflict / error 的 draft 语义、缓存与 Tree entry 收敛、Use Mine / Use Remote、UTF-8 保真。
 */
@RunWith(AndroidJUnit4::class)
class NoteRepositoryWriteTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var cache: ContentCache
    private lateinit var repository: NoteRepository
    private lateinit var cacheDir: File
    private lateinit var settings: SettingsRepository

    /** 测试前设备上的真实设置 —— tearDown 恢复，不在任何设备上留下副作用。 */
    private var priorSettings: com.obsidiancompanion.data.settings.AppSettings? = null

    private val path = "Java/Spring Boot.md"
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() = runTest {
        server = MockWebServer()
        server.start()
        val remote = GitHubRemoteDataSource(
            api = GitHubApiFactory.create(server.url("/").toString()) { "tok" },
            baseUrl = server.url("/"),
            tokenProvider = { "tok" },
        )
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        // 测试进程运行在目标 App uid 下，测试包私有目录不可写 —— 一律用目标 App Context
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        cacheDir = File(appContext.cacheDir, "note_write_test_cache").apply { deleteRecursively() }
        cache = ContentCache(cacheDir)
        settings = SettingsRepository(appContext)
        priorSettings = settings.flow.first()
        settings.setRepository("o", "r", "main")
        repository = NoteRepository(RepositoryIndexRepository(remote, db, settings), remote, cache, settings, db)
        db.repoEntryDao().insertAll(listOf(entry("abc123")))
    }

    @After
    fun tearDown() = runTest {
        server.shutdown()
        db.close()
        cacheDir.deleteRecursively()
        // 恢复设备原设置（好公民：connectedAndroidTest 不清空用户的仓库选择）
        val prior = priorSettings
        if (prior?.owner != null && prior.repo != null && prior.defaultBranch != null) {
            settings.setRepository(prior.owner, prior.repo, prior.defaultBranch)
            settings.setOnboarded(prior.isOnboarded)
        } else {
            settings.clearRepository()
        }
    }

    private fun entry(sha: String) = RepoEntryEntity(
        repoId = "o/r",
        path = path,
        name = "Spring Boot.md",
        parentPath = "Java",
        kind = EntryKind.MARKDOWN,
        blobSha = sha,
        size = 10L,
        observedChangedAt = null,
    )

    private fun json200(newSha: String, commitSha: String = "c999"): MockResponse =
        MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody("""{"content":{"sha":"$newSha"},"commit":{"sha":"$commitSha"}}""")

    /* ── §45：baseSha ABC / remote ABC → save success ─────────── */

    @Test
    fun saveSuccess_updatesCacheEntryAndClearsDraft() = runTest {
        // §43：中文 / emoji / WikiLink / Callout / CRLF 全部保真
        val content = "# 标题\r\n\r\n中文 🎉 [[其他笔记]]\r\n\r\n> [!note] 提示\r\n> 内容\r\n"
        server.enqueue(json200("def456"))

        val r = repository.saveNote(path, baseSha = "abc123", content = content)
        assertTrue(r is NoteSaveResult.Saved)
        assertEquals("def456", (r as NoteSaveResult.Saved).newSha)

        // §42：PUT body = message + base64 content + sha + branch
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/repos/o/r/contents/Java/Spring%20Boot.md", req.path)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("mobile: update Spring Boot.md", body["message"]!!.jsonPrimitive.content)
        assertEquals("abc123", body["sha"]!!.jsonPrimitive.content)
        assertEquals("main", body["branch"]!!.jsonPrimitive.content)
        val decoded = java.util.Base64.getDecoder().decode(body["content"]!!.jsonPrimitive.content)
        assertEquals(content, String(decoded, Charsets.UTF_8)) // CRLF 原样保留（§44）

        // §11/§48：新 SHA 立即写缓存 —— Reader 返回直接读，不重新下载
        assertEquals(content, String(cache.get("def456")!!, Charsets.UTF_8))
        // §10/§50：entry 指向新 blob 且 observedChangedAt = now
        val e = db.repoEntryDao().get("o/r", path)!!
        assertEquals("def456", e.blobSha)
        assertNotNull(e.observedChangedAt)
        // §21：保存成功 → draft 清除
        assertNull(db.pendingEditDao().get("o/r", path))
    }

    /* ── §45：baseSha ABC / remote DEF → conflict ─────────────── */

    @Test
    fun saveConflict_keepsDraftAndDoesNotTouchEntryOrCache() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))
        val r = repository.saveNote(path, baseSha = "abc123", content = "我的修改")
        assertTrue(r is NoteSaveResult.Conflict)

        // §21/§46：conflict → draft 保留（我的修改不丢）
        val draft = db.pendingEditDao().get("o/r", path)!!
        assertEquals("我的修改", draft.content)
        assertEquals("abc123", draft.baseSha)
        // 本地状态不变：entry 仍指旧 blob，不产生任何新缓存 / 不静默覆盖（§15）
        assertEquals("abc123", db.repoEntryDao().get("o/r", path)!!.blobSha)
        assertEquals(0, cache.count())
    }

    /* ── §46：save error → draft 保留 ─────────────────────────── */

    @Test
    fun saveError_keepsDraft() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val r = repository.saveNote(path, baseSha = "abc123", content = "修改内容")
        assertTrue(r is NoteSaveResult.Error)
        assertEquals(DomainError.ServerError, (r as NoteSaveResult.Error).error)
        assertEquals("修改内容", db.pendingEditDao().get("o/r", path)!!.content)
        assertEquals("abc123", db.repoEntryDao().get("o/r", path)!!.blobSha)
    }

    @Test
    fun saveUnauthorized_mapsToUnauthorized() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val r = repository.saveNote(path, baseSha = "abc123", content = "x")
        assertEquals(DomainError.Unauthorized, (r as NoteSaveResult.Error).error)
        assertNotNull(db.pendingEditDao().get("o/r", path))
    }

    @Test
    fun saveNotFound_mapsToNotFound() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val r = repository.saveNote(path, baseSha = "abc123", content = "x")
        assertEquals(DomainError.NotFound, (r as NoteSaveResult.Error).error)
        assertNotNull(db.pendingEditDao().get("o/r", path))
    }

    /* ── §21 Draft 恢复：base 必须 = draft.baseSha（不 rebase 到当前 entry SHA）── */

    @Test
    fun restoreDraft_remoteDiverged_putUsesDraftBaseShaAndConflicts() = runTest {
        // 1) 基于 abc123 编辑，保存失败（5xx）→ draft 留存（base = abc123）
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(repository.saveNote(path, baseSha = "abc123", content = "我的修改") is NoteSaveResult.Error)

        // 2) 期间 PC 改了这篇笔记 → Tree 刷新后本地 entry 指向新 blob（def456）
        db.repoEntryDao().updateBlobAfterSave("o/r", path, "def456", 20L, 222L)

        // 3) 恢复 draft 后保存：base 必须用 draft.baseSha（abc123），而不是当前 entry SHA（def456）
        val draft = repository.getPendingEdit(path)!!
        server.enqueue(MockResponse().setResponseCode(409))
        val r = repository.saveNote(path, baseSha = draft.baseSha, content = draft.content)

        // 远端已分叉 → 409 → Conflict（绝不静默覆盖 PC 的修改），draft 保留
        assertTrue(r is NoteSaveResult.Conflict)
        server.takeRequest() // 第一次 PUT（500，失败暂存）
        val put = server.takeRequest() // 恢复后的 PUT：sha 必须是草稿原 base
        assertEquals("abc123", json.parseToJsonElement(put.body.readUtf8()).jsonObject["sha"]!!.jsonPrimitive.content)
        assertEquals("我的修改", db.pendingEditDao().get("o/r", path)!!.content)
    }

    @Test
    fun restoreDraft_remoteUnchanged_savesDirectlyWithoutConflict() = runTest {
        // 常见场景：保存时失败留存 draft，之后远端没变 —— draft.baseSha 仍等于远端最新 → 直接保存成功，无额外摩擦
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(repository.saveNote(path, baseSha = "abc123", content = "我的修改") is NoteSaveResult.Error)

        val draft = repository.getPendingEdit(path)!!
        server.enqueue(json200("def456"))
        val r = repository.saveNote(path, baseSha = draft.baseSha, content = draft.content + " 续写")

        assertTrue(r is NoteSaveResult.Saved)
        server.takeRequest() // 第一次 PUT（500，失败暂存）
        val put = server.takeRequest() // 恢复后的 PUT：sha = 草稿原 base = 远端当前
        assertEquals("abc123", json.parseToJsonElement(put.body.readUtf8()).jsonObject["sha"]!!.jsonPrimitive.content)
        assertNull(db.pendingEditDao().get("o/r", path))
    }

    /* ── §45 Use Mine：fetch DEF → PUT using DEF → success ────── */

    @Test
    fun conflictThenUseMine_fetchesLatestShaAndPuts() = runTest {
        // 1) 保存 → 409（draft 保留）
        server.enqueue(MockResponse().setResponseCode(409))
        assertTrue(repository.saveNote(path, "abc123", "我的修改 🚀") is NoteSaveResult.Conflict)

        // 2) Use Mine：先 GET 最新（DEF）→ 再 PUT（用 DEF 作为 sha）
        val remoteText = "远端版本"
        val b64 = java.util.Base64.getEncoder().encodeToString(remoteText.toByteArray())
        server.enqueue(
            MockResponse().addHeader("Content-Type", "application/json")
                .setBody("""{"sha":"def456","encoding":"base64","content":"$b64"}"""),
        )
        server.enqueue(json200("ghi789"))

        val r = repository.resolveConflictUseMine(path, "我的修改 🚀")
        assertTrue(r is NoteSaveResult.Saved)
        assertEquals("ghi789", (r as NoteSaveResult.Saved).newSha)

        server.takeRequest() // 第一次 PUT（409 那次）
        server.takeRequest() // GET latest
        val put2 = server.takeRequest() // 第二次 PUT
        val body2 = json.parseToJsonElement(put2.body.readUtf8()).jsonObject
        assertEquals("def456", body2["sha"]!!.jsonPrimitive.content) // 用最新 SHA，不是过期 ABC
        val decoded = java.util.Base64.getDecoder().decode(body2["content"]!!.jsonPrimitive.content)
        assertEquals("我的修改 🚀", String(decoded, Charsets.UTF_8))

        // 收敛：缓存 / entry / draft
        assertEquals("我的修改 🚀", String(cache.get("ghi789")!!, Charsets.UTF_8))
        assertEquals("ghi789", db.repoEntryDao().get("o/r", path)!!.blobSha)
        assertNull(db.pendingEditDao().get("o/r", path))
    }

    @Test
    fun useMine_secondConflict_staysConflict() = runTest {
        val b64 = java.util.Base64.getEncoder().encodeToString("remote".toByteArray())
        server.enqueue(
            MockResponse().addHeader("Content-Type", "application/json")
                .setBody("""{"sha":"def456","content":"$b64"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(409))
        val r = repository.resolveConflictUseMine(path, "mine")
        assertTrue(r is NoteSaveResult.Conflict) // 远端又变了 —— 仍不静默覆盖
    }

    /* ── §45 Use Remote：discard mine → cache remote → Reader remote ── */

    @Test
    fun useRemote_cachesRemoteAndClearsDraft() = runTest {
        // 先有冲突 draft
        server.enqueue(MockResponse().setResponseCode(409))
        repository.saveNote(path, "abc123", "我的修改")
        assertNotNull(db.pendingEditDao().get("o/r", path))

        val remoteText = "# 远端最新\n\nPC 上的版本"
        val b64 = java.util.Base64.getEncoder().encodeToString(remoteText.toByteArray(Charsets.UTF_8))
        server.enqueue(
            MockResponse().addHeader("Content-Type", "application/json")
                .setBody("""{"sha":"def456","encoding":"base64","content":"$b64"}"""),
        )

        val r = repository.resolveConflictUseRemote(path)
        assertTrue(r is NoteSaveResult.Saved)
        // 远端正文写缓存 + entry 指向远端 SHA → Reader 直接显示远端版本
        assertEquals(remoteText, String(cache.get("def456")!!, Charsets.UTF_8))
        assertEquals("def456", db.repoEntryDao().get("o/r", path)!!.blobSha)
        assertNull(db.pendingEditDao().get("o/r", path))
    }

    /* ── §49：编辑不影响 favorite / recentRead（identity = repoId+path）── */

    @Test
    fun saveSuccess_keepsFavoriteAndRecentRead() = runTest {
        db.noteMetadataDao().setFavorite("o/r", path, true)
        db.noteMetadataDao().markRead("o/r", path, 111L)
        server.enqueue(json200("def456"))
        repository.saveNote(path, "abc123", "new content")

        val meta = db.noteMetadataDao()
        val one = meta.observeOne("o/r", path).first()
        assertTrue(one!!.isFavorite)
        assertEquals(111L, one.lastReadAt)
    }

    /* ── createNote（新建 / 快速收集：仅创建，绝不覆盖）────────── */

    @Test
    fun createNote_success_insertsEntryCachesContent() = runTest {
        server.enqueue(json200("newsha1", "c1000"))
        val r = repository.createNote("Inbox/快速收集.md", "hello 分享 🎉")
        assertTrue(r is NoteSaveResult.Saved)
        assertEquals("newsha1", (r as NoteSaveResult.Saved).newSha)

        // PUT body 不带 sha（仅创建语义）
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/repos/o/r/contents/Inbox/%E5%BF%AB%E9%80%9F%E6%94%B6%E9%9B%86.md", req.path)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertTrue("sha" !in body)

        // 本地收敛：Tree 插入新 entry + 正文按 newSha 写缓存 → openNote 立即可读（无需整树刷新）
        val e = db.repoEntryDao().get("o/r", "Inbox/快速收集.md")!!
        assertEquals(EntryKind.MARKDOWN, e.kind)
        assertEquals("Inbox", e.parentPath)
        assertEquals("newsha1", e.blobSha)
        assertNotNull(e.observedChangedAt)
        val opened = repository.openNote("Inbox/快速收集.md")
        assertTrue(opened is NoteOpenResult.Content)
        assertEquals("hello 分享 🎉", (opened as NoteOpenResult.Content).markdown)
    }

    @Test
    fun createNote_existingPath_conflictsWithoutHttp() = runTest {
        val r = repository.createNote(path, "x") // setUp 已插入该 entry
        assertTrue(r is NoteSaveResult.Conflict)
        assertEquals(0, server.requestCount) // 本地预检拦下，不发请求
    }

    @Test
    fun createNote_remote422_conflicts() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Invalid request"}"""))
        val r = repository.createNote("Inbox/B.md", "x")
        assertTrue(r is NoteSaveResult.Conflict)
        assertNull(db.repoEntryDao().get("o/r", "Inbox/B.md")) // 未收敛任何本地状态
    }
}
