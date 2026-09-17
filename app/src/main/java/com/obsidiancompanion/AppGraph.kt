package com.obsidiancompanion

import android.content.Context
import com.obsidiancompanion.data.cache.ContentCache
import com.obsidiancompanion.data.credentials.CredentialStore
import com.obsidiancompanion.data.github.GitHubApi
import com.obsidiancompanion.data.github.GitHubApiFactory
import com.obsidiancompanion.data.github.GitHubRemoteDataSource
import com.obsidiancompanion.data.metadata.AppDatabase
import com.obsidiancompanion.data.network.NetworkMonitor
import com.obsidiancompanion.data.repository.ImageRepository
import com.obsidiancompanion.data.repository.NoteRepository
import com.obsidiancompanion.data.repository.RepositoryIndexRepository
import com.obsidiancompanion.data.repository.VaultLinkResolver
import com.obsidiancompanion.data.settings.SettingsRepository

/**
 * 轻量 ServiceLocator（V1 不引入 Hilt/Koin）—— Application.onCreate 初始化，进程内单例。
 * 后续批次（GitHub / Room / Cache / Repository）在本对象上继续追加。
 */
object AppGraph {

    lateinit var settings: SettingsRepository
        private set
    lateinit var credentials: CredentialStore
        private set
    lateinit var network: NetworkMonitor
        private set

    val githubRetrofit: retrofit2.Retrofit by lazy {
        GitHubApiFactory.createRetrofit(BuildConfig.GITHUB_API_BASE_URL) { credentials.getToken() }
    }
    val githubApi: GitHubApi by lazy { githubRetrofit.create(GitHubApi::class.java) }
    val githubRemote: GitHubRemoteDataSource by lazy {
        GitHubRemoteDataSource(githubApi, githubRetrofit.baseUrl()) { credentials.getToken() }
    }

    val database: AppDatabase by lazy { AppDatabase.build(appContext!!) }
    val contentCache: ContentCache by lazy { ContentCache(appContext!!) }
    val indexRepository: RepositoryIndexRepository by lazy {
        RepositoryIndexRepository(githubRemote, database, settings)
    }
    val noteRepository: NoteRepository by lazy {
        NoteRepository(indexRepository, githubRemote, contentCache, settings, database)
    }

    /**
     * 进程级 scope（Phase 5 §13）：保存成功后异步 refreshTree 用 ——
     * Editor/Conflict 立即返回，不让 UI 等整树刷新；SupervisorJob 隔离单次失败。
     */
    val appScope: kotlinx.coroutines.CoroutineScope by lazy {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        )
    }

    /** WikiLink / 嵌入目标解析（Phase 4）：每次解析直接读 Room Tree，Tree 刷新后自然最新。 */
    val linkResolver: VaultLinkResolver by lazy {
        VaultLinkResolver { repoId -> database.repoEntryDao().getAll(repoId) }
    }
    val imageRepository: ImageRepository by lazy {
        ImageRepository(linkResolver, githubRemote, contentCache, settings, network)
    }

    /** Phase 6B：自动刷新触发器（foreground / network recovery），仍走唯一 refreshTree(force=false)。 */
    val refreshTriggers: RefreshTriggers by lazy {
        RefreshTriggers(appScope, settings, network, indexRepository)
    }

    /**
     * 系统分享进来的文本（ACTION_SEND）：MainActivity 写入，AppNavGraph 观察并打开快速收集页。
     * StateFlow 暂存 —— App 冷启动时导航尚未就绪也不丢。
     */
    val pendingSharedText = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    @Volatile
    private var appContext: Context? = null

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        appContext = app
        settings = SettingsRepository(app)
        credentials = CredentialStore(app)
        network = NetworkMonitor(app)
        initialized = true
    }
}
