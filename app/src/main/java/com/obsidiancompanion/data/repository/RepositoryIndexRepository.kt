package com.obsidiancompanion.data.repository

import androidx.room.withTransaction
import com.obsidiancompanion.data.github.GitHubRemoteDataSource
import com.obsidiancompanion.data.github.GitHubResult
import com.obsidiancompanion.data.metadata.AppDatabase
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.data.metadata.entities.RepositoryStateEntity
import com.obsidiancompanion.data.settings.SettingsRepository
import com.obsidiancompanion.model.DomainError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 单次 refresh 的结果（§19-21）。 */
sealed interface RefreshOutcome {
    data class Success(val diff: TreeDiff.Result, val at: Long) : RefreshOutcome

    /**
     * 两种含义：(1) 服务器 304 —— 远端确认无变化；(2) Phase 6B freshness window 的本地去重 ——
     * 根本没询问服务器（远端可能刚变过）。当前所有读取返回值的调用方都是 force=true（遇不到去重），
     * 因此是安全的；将来若给自动刷新加「已是最新」类结果提示，必须先区分这两种情况。
     */
    data class NotModified(val at: Long) : RefreshOutcome
    data class Failed(val error: DomainError, val at: Long) : RefreshOutcome
}

/** UI 全局刷新状态（Home chip / SyncScreen 共用）。 */
data class RefreshUiState(
    val refreshing: Boolean = false,
    val lastOutcome: RefreshOutcome? = null,
)

/**
 * RepositoryIndexRepository（§38）：组合 GitHub + Room Tree Cache。
 * 冷启动自动刷新与手动刷新共用 refreshTree()（§20）；Mutex 单飞（§63）；
 * ETag/304 不重建 Room 只更新 freshness（§19）；refresh 绝不下载正文（§62）。
 */
class RepositoryIndexRepository(
    private val remote: GitHubRemoteDataSource,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val throttle: RefreshThrottle = RefreshThrottle(),
) {

    private val refreshMutex = Mutex()

    private val _refreshUiState = MutableStateFlow(RefreshUiState())
    val refreshUiState: StateFlow<RefreshUiState> = _refreshUiState.asStateFlow()

    private companion object {
        /** SQLite 单语句变量上限保险值（老设备 999），批量删除按此分片。 */
        const val DELETE_CHUNK = 500
    }

    /**
     * 唯一刷新入口（§20）。Phase 6B：force=false（自动触发）受 freshness window 去重 ——
     * 刚成功刷新过就静默跳过（不发 HTTP、不动 UI 状态）；force=true（手动刷新）始终直接请求。
     * Mutex 单飞（§63）保持不变：并发触发最多一个真实 Tree 请求。
     */
    suspend fun refreshTree(force: Boolean = false): RefreshOutcome = refreshMutex.withLock {
        val s = settings.flow.first()
        val repoId = s.repoId
            ?: return RefreshOutcome.Failed(DomainError.Unknown, System.currentTimeMillis())
        val branch = s.defaultBranch
            ?: return RefreshOutcome.Failed(DomainError.Unknown, System.currentTimeMillis())

        val enteredAt = throttle.nowMs()
        if (!force && throttle.shouldSkipAutoAt(enteredAt)) {
            return RefreshOutcome.NotModified(enteredAt)
        }

        _refreshUiState.value = _refreshUiState.value.copy(refreshing = true)
        try {
            val prior = db.repositoryStateDao().get(repoId)
            val now = System.currentTimeMillis()
            val outcome: RefreshOutcome = when (val r = remote.getTree(s.owner!!, s.repo!!, branch, prior?.etag)) {
                is GitHubResult.NotModified -> {
                    if (prior != null) {
                        db.repositoryStateDao().upsert(
                            prior.copy(
                                etag = r.etag ?: prior.etag,
                                lastRefreshAt = now,
                                lastRefreshResult = "NOT_MODIFIED",
                            ),
                        )
                    }
                    RefreshOutcome.NotModified(now)
                }

                is GitHubResult.Ok -> {
                    val old = db.repoEntryDao().getAll(repoId)
                    val diff = TreeDiff.compute(repoId, old, r.value, now)
                    db.withTransaction {
                        // 增量入库：只写 Added/Changed/Deleted 行 —— 不再整表 delete+insert；
                        // 无变化的刷新对 repo_entries 零写入，Room 观察流不再被无意义重放
                        val upserts = diff.upsertEntries()
                        if (upserts.isNotEmpty()) db.repoEntryDao().insertAll(upserts)
                        diff.deletedPaths.chunked(DELETE_CHUNK).forEach { chunk ->
                            db.repoEntryDao().deleteByPaths(repoId, chunk)
                        }
                        db.repositoryStateDao().upsert(
                            RepositoryStateEntity(
                                repoId = repoId,
                                branch = branch,
                                rootTreeSha = r.value.rootSha,
                                etag = r.value.etag,
                                lastRefreshAt = now,
                                lastRefreshResult = "SUCCESS",
                                entryCount = diff.entries.size,
                                markdownCount = diff.entries.count { it.kind == com.obsidiancompanion.data.metadata.entities.EntryKind.MARKDOWN },
                            ),
                        )
                    }
                    RefreshOutcome.Success(diff, now)
                }

                is GitHubResult.Fail -> RefreshOutcome.Failed(r.error, now)
            }
            // Success / NotModified 都算「刚刷新过」（304 说明远端确实没变）；Failed 不改变 fresh 状态
            if (outcome !is RefreshOutcome.Failed) throttle.markSuccessAt(throttle.nowMs())
            _refreshUiState.value = RefreshUiState(refreshing = false, lastOutcome = outcome)
            outcome
        } catch (e: Exception) {
            val outcome = RefreshOutcome.Failed(DomainError.Unknown, System.currentTimeMillis())
            _refreshUiState.value = RefreshUiState(refreshing = false, lastOutcome = outcome)
            outcome
        }
    }

    /**
     * 前台刷新阈值判断（Phase 6B §8）：距上次成功刷新是否已 ≥ minIntervalMs。
     * 从未成功过时（lastSuccessAt=0）真机时钟下恒为 true —— 这正是门控期望的
     * 「无成功记录 = 无限陈旧 = 应该刷新」；假时钟测试需先制造一次成功再测阈值。
     */
    fun elapsedSinceSuccessAtLeast(minIntervalMs: Long): Boolean =
        throttle.nowMs() - throttle.lastSuccessAt >= minIntervalMs

    fun observeTree(repoId: String): Flow<List<RepoEntryEntity>> =
        db.repoEntryDao().observeAll(repoId)

    fun observeEntry(repoId: String, path: String): Flow<RepoEntryEntity?> =
        db.repoEntryDao().observeOne(repoId, path)

    suspend fun getEntry(repoId: String, path: String): RepoEntryEntity? =
        db.repoEntryDao().get(repoId, path)

    fun observeRepoState(repoId: String): Flow<RepositoryStateEntity?> =
        db.repositoryStateDao().observe(repoId)

    /** 重连/切换仓库时清理旧 Repository Tree Cache 与其 state（§68）。收藏/最近阅读按 repoId 隔离保留。 */
    suspend fun clearRepositoryData(repoId: String) {
        db.withTransaction {
            db.repoEntryDao().deleteByRepo(repoId)
        }
        // state 行直接删除（无 @Delete 简化为整表清当前行）
        db.repositoryStateDao().upsert(
            RepositoryStateEntity(
                repoId = repoId,
                branch = "",
                rootTreeSha = null,
                etag = null,
                lastRefreshAt = null,
                lastRefreshResult = "CLEARED",
            ),
        )
        _refreshUiState.value = RefreshUiState()
    }
}
