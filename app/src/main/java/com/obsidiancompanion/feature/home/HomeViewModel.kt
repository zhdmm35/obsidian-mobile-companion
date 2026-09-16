package com.obsidiancompanion.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.autoRefreshAllowed
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.data.mock.ConflictDemo
import com.obsidiancompanion.data.repository.RefreshOutcome
import com.obsidiancompanion.data.repository.RefreshUiState
import com.obsidiancompanion.model.SyncStatus
import com.obsidiancompanion.util.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 列表行展示模型（首页 / 搜索等共用）。 */
data class NoteRowUi(
    val path: String,
    val title: String,
    val folder: String,
    val timeLabel: String?,
)

data class HomeUiState(
    val loading: Boolean = true,
    val status: SyncStatus = SyncStatus.UPDATED,
    val isOffline: Boolean = false,
    val recentModified: List<NoteRowUi> = emptyList(),
    val recentRead: List<NoteRowUi> = emptyList(),
    val favorites: List<NoteRowUi> = emptyList(),
    val totalNotes: Int = 0,
)

/**
 * Home（§24）：最近修改 = observedChangedAt DESC（检测到的远端变化时间，§22）；
 * 最近阅读 / 收藏 = Room；全部笔记计数 = Tree Cache。全部本地即时，不依赖实时网络。
 * 冷启动自动刷新（§3/§20）：settings.autoRefresh 且在线时后台 refreshTree，不阻塞 UI。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel : ViewModel() {

    private val settings = AppGraph.settings
    private val index = AppGraph.indexRepository
    private val network = AppGraph.network

    val uiState: StateFlow<HomeUiState> = settings.flow.flatMapLatest { s ->
        val repoId = s.repoId
            ?: return@flatMapLatest flowOf(HomeUiState(loading = false))
        combine(
            index.observeTree(repoId),
            AppGraph.database.noteMetadataDao().observeFavorites(repoId),
            AppGraph.database.noteMetadataDao().observeRecentRead(repoId, 10),
            index.refreshUiState,
            network.isOnlineFlow,
        ) { tree, favorites, recentRead, refresh, online ->
            val markdown = tree.filter { it.kind == EntryKind.MARKDOWN }
            HomeUiState(
                loading = false,
                status = chipStatus(refresh, online),
                isOffline = !online,
                recentModified = markdown
                    .filter { it.observedChangedAt != null }
                    .sortedByDescending { it.observedChangedAt!! }
                    .take(5)
                    .map { it.toRow(Format.relativeTime(it.observedChangedAt)) },
                recentRead = recentRead.mapNotNull { read ->
                    markdown.find { it.path == read.path }?.toRow(Format.relativeTime(read.lastReadAt))
                },
                favorites = favorites.mapNotNull { fav ->
                    markdown.find { it.path == fav.path }?.toRow(null)
                },
                totalNotes = markdown.size,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            val s = settings.flow.first()
            // Phase 6B §10：与 RefreshTriggers 共用同一门控判定（JVM 直测）
            if (autoRefreshAllowed(s.autoRefresh, s.repoId != null, network.isOnline)) {
                index.refreshTree()
            }
        }
    }

    private fun chipStatus(refresh: RefreshUiState, online: Boolean): SyncStatus = when {
        refresh.refreshing -> SyncStatus.REFRESHING
        ConflictDemo.enabled -> SyncStatus.CONFLICT
        refresh.lastOutcome is RefreshOutcome.Failed && online -> SyncStatus.REFRESH_FAILED
        !online -> SyncStatus.OFFLINE
        else -> SyncStatus.UPDATED
    }

    private fun RepoEntryEntity.toRow(timeLabel: String?): NoteRowUi = NoteRowUi(
        path = path,
        title = name.removeSuffix(".md"),
        folder = parentPath ?: "根目录",
        timeLabel = timeLabel,
    )
}
