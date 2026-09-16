package com.obsidiancompanion.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obsidiancompanion.RefreshTriggers
import com.obsidiancompanion.data.github.GitHubApiFactory
import com.obsidiancompanion.data.github.GitHubRemoteDataSource
import com.obsidiancompanion.data.metadata.AppDatabase
import com.obsidiancompanion.data.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Phase 6B §16 刷新可靠性（HTTP 级语义）：
 * freshness 去重 / 手动 force 强制 / single-flight / 自动关闭时手动仍可用 / 前台阈值。
 * 复用 Phase 5 测试模式：MockWebServer + in-memory Room + 真实 SettingsRepository（测试包隔离）+ 假时钟。
 */
@RunWith(AndroidJUnit4::class)
class RepositoryIndexRefreshTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: RepositoryIndexRepository
    private var priorSettings: com.obsidiancompanion.data.settings.AppSettings? = null

    /** 假时钟：refreshTree 的 freshness window 全部走这个时间。 */
    private var fakeNow = 1_000_000L

    private fun treeJson(sha: String = "r${System.nanoTime()}") =
        MockResponse().addHeader("Content-Type", "application/json")
            .setBody("""{"sha":"$sha","truncated":false,"tree":[{"path":"A.md","type":"blob","sha":"a1","size":3}]}""")

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
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        settings = SettingsRepository(appContext)
        priorSettings = settings.flow.first()
        settings.setRepository("o", "r", "main")
        repository = RepositoryIndexRepository(
            remote, db, settings,
            throttle = RefreshThrottle(freshnessWindowMs = 30_000, now = { fakeNow }),
        )
    }

    @After
    fun tearDown() = runTest {
        server.shutdown()
        db.close()
        // 恢复设备原设置（好公民：connectedAndroidTest 不清空用户的仓库选择）
        settings.setAutoRefresh(priorSettings?.autoRefresh ?: true)
        val prior = priorSettings
        if (prior?.owner != null && prior.repo != null && prior.defaultBranch != null) {
            settings.setRepository(prior.owner, prior.repo, prior.defaultBranch)
            settings.setOnboarded(prior.isOnboarded)
        } else {
            settings.clearRepository()
        }
    }

    @Test
    fun freshness_autoWithinWindow_skipsHttpAndKeepsUiState() = runTest {
        server.enqueue(treeJson())
        val first = repository.refreshTree()
        assertTrue(first is RefreshOutcome.Success)
        assertEquals(1, server.requestCount)

        fakeNow += 5_000 // window 内再次自动刷新
        val second = repository.refreshTree()
        assertTrue(second is RefreshOutcome.NotModified)
        assertEquals(1, server.requestCount) // 没有第二个 HTTP 请求
        // 静默跳过：UI 状态仍是第一次的真实结果（同一实例），未被跳过覆写
        org.junit.Assert.assertSame(first, repository.refreshUiState.value.lastOutcome)
    }

    @Test
    fun forceRefresh_withinWindow_stillRequestsHttp() = runTest {
        server.enqueue(treeJson())
        repository.refreshTree()
        assertEquals(1, server.requestCount)

        fakeNow += 1_000
        server.enqueue(treeJson())
        val forced = repository.refreshTree(force = true)
        assertTrue(forced is RefreshOutcome.Success)
        assertEquals(2, server.requestCount) // 手动刷新始终真实请求
    }

    @Test
    fun freshness_windowExpiry_allowsAutoAgain() = runTest {
        server.enqueue(treeJson())
        repository.refreshTree()
        fakeNow += 31_000 // 超过 window
        server.enqueue(treeJson())
        repository.refreshTree()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun failedRefresh_doesNotMarkFresh_nextAutoRetries() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(repository.refreshTree() is RefreshOutcome.Failed)
        fakeNow += 1_000 // window 内，但上次是失败 —— 自动刷新必须重试而不是跳过
        server.enqueue(treeJson())
        assertTrue(repository.refreshTree() is RefreshOutcome.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun singleFlight_twoConcurrentAutoRefreshes_onlyOneHttpRequest() = runTest {
        // 第一个请求被 MockWebServer 挂住 400ms，第二个 refresh 与之并发
        server.enqueue(
            treeJson().setHeadersDelay(400, TimeUnit.MILLISECONDS),
        )
        val a = async { repository.refreshTree() }
        val b = async { repository.refreshTree() }
        val ra = a.await()
        val rb = b.await()
        assertTrue(ra is RefreshOutcome.Success)
        assertTrue(rb is RefreshOutcome.NotModified) // 被 freshness window 去重
        assertEquals(1, server.requestCount)         // 真实 Tree HTTP 只发生一次
    }

    @Test
    fun autoRefreshDisabled_manualForceStillRequests() = runTest {
        settings.setAutoRefresh(false)
        // 关闭自动刷新只影响触发器层；手动 force 必须照常请求
        server.enqueue(treeJson())
        val forced = repository.refreshTree(force = true)
        assertTrue(forced is RefreshOutcome.Success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun foregroundThreshold_gatesByElapsedSinceSuccess() = runTest {
        server.enqueue(treeJson())
        repository.refreshTree()
        fakeNow += RefreshTriggers.FOREGROUND_MIN_INTERVAL_MS - 1
        assertFalse(repository.elapsedSinceSuccessAtLeast(RefreshTriggers.FOREGROUND_MIN_INTERVAL_MS))
        fakeNow += 1
        assertTrue(repository.elapsedSinceSuccessAtLeast(RefreshTriggers.FOREGROUND_MIN_INTERVAL_MS))
    }
}
