package com.obsidiancompanion.feature.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.BackTopBar
import com.obsidiancompanion.core.ui.KeyValueRow
import com.obsidiancompanion.core.ui.PrimaryButton
import com.obsidiancompanion.core.ui.SectionHeader
import com.obsidiancompanion.core.ui.SecondaryButton
import com.obsidiancompanion.data.mock.ConflictDemo
import com.obsidiancompanion.data.repository.RefreshOutcome
import com.obsidiancompanion.data.repository.RefreshUiState
import com.obsidiancompanion.data.settings.AppSettings
import com.obsidiancompanion.model.DomainError
import com.obsidiancompanion.model.SyncStatus
import com.obsidiancompanion.util.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SyncUiState(
    val settings: AppSettings? = null,
    val refresh: RefreshUiState = RefreshUiState(),
    val isOffline: Boolean = false,
    val cachedNotes: Int = 0,
    val cacheSizeLabel: String = "—",
    val markdownCount: Int = 0,
    val treeSha: String? = null,
    val lastRefreshLabel: String? = null,
) {
    val status: SyncStatus
        get() = when {
            refresh.refreshing -> SyncStatus.REFRESHING
            ConflictDemo.enabled -> SyncStatus.CONFLICT
            refresh.lastOutcome is RefreshOutcome.Failed && !isOffline -> SyncStatus.REFRESH_FAILED
            isOffline -> SyncStatus.OFFLINE
            else -> SyncStatus.UPDATED
        }

    val failure: RefreshOutcome.Failed?
        get() = if (status == SyncStatus.REFRESH_FAILED) refresh.lastOutcome as? RefreshOutcome.Failed else null
}

/**
 * 数据刷新页（§56）：语义从「同步」改为 Repository / Data Refresh Status。
 * 同一 refreshTree() 入口（§20）；技术详情只出现 Repository/Branch/Tree SHA/Last Refresh/Cache Size。
 */
class SyncViewModel : ViewModel() {

    private val cacheStats = MutableStateFlow(0 to "—") // cachedNotes to sizeLabel

    val uiState: StateFlow<SyncUiState> = combine(
        AppGraph.settings.flow,
        AppGraph.indexRepository.refreshUiState,
        AppGraph.network.isOnlineFlow,
        cacheStats,
    ) { s, refresh, online, (cachedNotes, sizeLabel) ->
        SyncUiState(
            settings = s,
            refresh = refresh,
            isOffline = !online,
            cachedNotes = cachedNotes,
            cacheSizeLabel = sizeLabel,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState())

    /** Tree 元数据（branch/sha/count/lastRefresh）单独驱动，避免嵌套收集。 */
    private val treeMeta = MutableStateFlow<TreeMeta?>(null)

    val meta: StateFlow<TreeMeta?> get() = treeMeta.asStateFlow()

    init {
        refreshCacheStats()
        observeMeta()
        // 刷新结束时更新缓存统计
        viewModelScope.launch {
            AppGraph.indexRepository.refreshUiState.collect { refresh ->
                if (!refresh.refreshing) refreshCacheStats()
            }
        }
    }

    private fun observeMeta() {
        viewModelScope.launch {
            AppGraph.settings.flow.collect { s ->
                val repoId = s.repoId
                if (repoId == null) {
                    treeMeta.value = null
                } else {
                    AppGraph.indexRepository.observeRepoState(repoId).collect { st ->
                        treeMeta.value = TreeMeta(
                            branch = st?.branch,
                            treeSha = st?.rootTreeSha,
                            markdownCount = st?.markdownCount ?: 0,
                            lastRefreshAt = st?.lastRefreshAt,
                        )
                    }
                }
            }
        }
    }

    fun refreshCacheStats() {
        viewModelScope.launch {
            val stat = AppGraph.contentCache.stat()
            cacheStats.value = stat.count to Format.bytes(stat.bytes)
        }
    }

    private var refreshJob: Job? = null

    fun refreshNow(onMessage: (String) -> Unit) {
        // force=true 会绕过 freshness window；连点不能向 Mutex 排队多个手动请求。
        if (refreshJob?.isActive == true || AppGraph.indexRepository.refreshUiState.value.refreshing) return
        refreshJob = viewModelScope.launch {
            // 用户手动「立即刷新」：始终强制请求，不受 freshness window 去重（Phase 6B §5）
            when (val outcome = AppGraph.indexRepository.refreshTree(force = true)) {
                is RefreshOutcome.Success ->
                    onMessage(if (outcome.diff.hasChanges) "已更新" else "已是最新")
                is RefreshOutcome.NotModified -> onMessage("已是最新")
                is RefreshOutcome.Failed -> onMessage(refreshFailureMessage(outcome.error))
            }
            refreshCacheStats()
        }
    }
}

data class TreeMeta(val branch: String?, val treeSha: String?, val markdownCount: Int, val lastRefreshAt: Long?)

/**
 * 刷新失败 → 中文原因（snackbar 与失败卡共用；不再向用户暴露 Forbidden 等错误名）。
 * 鉴权错误可在失败卡提供「重新设置 Token」出口。
 */
internal fun refreshFailureMessage(error: DomainError): String = when (error) {
    DomainError.Unauthorized -> "Token 无效或已过期，请重新设置 Token"
    DomainError.Forbidden -> "Token 无权读取该仓库，请检查 Token 权限"
    DomainError.NotFound -> "仓库不存在或已失去访问权限"
    DomainError.RateLimited -> "GitHub 接口限流，请稍后再试"
    DomainError.NetworkUnavailable -> "无法连接 GitHub，请检查网络后重试"
    DomainError.ServerError -> "GitHub 服务暂时不可用，请稍后再试"
    DomainError.MalformedResponse -> "GitHub 返回了无法解析的数据，请稍后再试"
    else -> "刷新失败，请稍后再试"
}

private val DomainError.isAuthError: Boolean
    get() = this == DomainError.Unauthorized || this == DomainError.Forbidden

private data class HeroSpec(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    val iconTint: Color,
    val spinning: Boolean,
)

@Composable
fun SyncScreen(
    onBack: () -> Unit,
    onOpenConflict: () -> Unit,
    onOpenToken: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    viewModel: SyncViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val meta: TreeMeta? by viewModel.meta.collectAsState()
    var techExpanded by rememberSaveable { mutableStateOf(false) }

    val hero = when (state.status) {
        SyncStatus.UPDATED -> HeroSpec(
            AppIcons.CheckCircle, "已更新",
            "GitHub 仓库内容已是最新，上次刷新 ${meta?.lastRefreshAt?.let { Format.relativeTime(it) } ?: "—"}",
            AppColors.textPrimary, false,
        )
        SyncStatus.REFRESHING -> HeroSpec(
            AppIcons.Refresh, "正在刷新",
            "正在从 GitHub 检查更新，不影响阅读", AppColors.textPrimary, true,
        )
        SyncStatus.OFFLINE -> HeroSpec(
            AppIcons.CloudOff, "离线",
            "浏览与搜索基于缓存，未缓存的笔记需联网后打开", AppColors.textTertiary, false,
        )
        SyncStatus.REFRESH_FAILED -> HeroSpec(
            AppIcons.Alert, "刷新失败",
            "缓存内容仍可正常阅读，请按下方提示处理", AppColors.danger, false,
        )
        SyncStatus.CONFLICT -> HeroSpec(
            AppIcons.Alert, "有冲突",
            "1 篇笔记在两台设备上都有修改，需要你确认（演示态）", AppColors.danger, false,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = AppSpacing.screenBottomPadding),
    ) {
        BackTopBar(title = "数据刷新", onBack = onBack)

        // SyncHero（原型 .syn-hero）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 30.dp, start = AppSpacing.xxl, end = AppSpacing.xxl, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(AppColors.surface)
                    .border(1.dp, AppColors.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (hero.spinning) {
                    SpinningIcon(hero.icon, tint = AppColors.textPrimary, size = 24.dp, contentDescription = hero.title)
                } else {
                    Icon(hero.icon, contentDescription = hero.title, tint = hero.iconTint, modifier = Modifier.size(24.dp))
                }
            }
            Text(hero.title, style = AppTypography.titleMedium)
            Text(hero.subtitle, style = AppTypography.bodySmall, color = AppColors.textTertiary)
        }

        // 概览（§56 文案）
        SectionHeader("概览")
        Column(Modifier.fillMaxWidth()) {
            KeyValueRow("上次刷新", meta?.lastRefreshAt?.let { Format.relativeTime(it) } ?: "—")
            KeyValueRow("缓存笔记", "${state.cachedNotes} 篇")
            KeyValueRow(
                "Repository",
                state.settings?.repoId ?: "未连接",
            )
            KeyValueRow("笔记总数", "${meta?.markdownCount ?: 0} 篇")
        }

        // 立即刷新（与冷启动同一 refreshTree 入口 §20）
        PrimaryButton(
            text = if (state.refresh.refreshing) "正在刷新…" else "立即刷新",
            onClick = { viewModel.refreshNow(onShowSnackbar) },
            enabled = !state.refresh.refreshing,
            block = true,
            modifier = Modifier.padding(
                start = AppSpacing.screenPaddingHorizontal,
                end = AppSpacing.screenPaddingHorizontal,
                top = 18.dp,
            ),
        )

        // 刷新失败卡：中文原因 + 对应解决入口（鉴权错误可重设 Token；始终可重试）
        state.failure?.let { failed ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppSpacing.screenPaddingHorizontal,
                        end = AppSpacing.screenPaddingHorizontal,
                        top = 16.dp,
                    )
                    .clip(AppShapes.small)
                    .background(AppColors.surface)
                    .border(1.dp, AppColors.border, AppShapes.small)
                    .padding(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(AppIcons.Alert, contentDescription = null, tint = AppColors.danger, modifier = Modifier.size(16.dp))
                    Text("刷新失败", style = AppTypography.titleSmall, color = AppColors.danger)
                }
                Text(
                    refreshFailureMessage(failed.error),
                    style = AppTypography.bodySmall,
                    color = AppColors.textTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (failed.error.isAuthError) {
                        PrimaryButton(text = "重新设置 Token", onClick = onOpenToken, small = true, block = true)
                    }
                    SecondaryButton(
                        text = "重试",
                        onClick = { viewModel.refreshNow(onShowSnackbar) },
                        enabled = !state.refresh.refreshing,
                        small = true,
                        block = true,
                    )
                }
            }
        }

        // 冲突卡（仅 DEBUG 演示态；真实冲突 Phase 5 产生）
        if (ConflictDemo.enabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppSpacing.screenPaddingHorizontal,
                        end = AppSpacing.screenPaddingHorizontal,
                        top = 16.dp,
                    )
                    .clip(AppShapes.small)
                    .background(AppColors.conflictBg)
                    .border(1.dp, AppColors.conflictBorder, AppShapes.small)
                    .padding(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(AppIcons.Alert, contentDescription = null, tint = AppColors.danger, modifier = Modifier.size(16.dp))
                    Text("Agent设计.md", style = AppTypography.titleSmall, color = AppColors.danger)
                }
                Text(
                    "电脑和手机都修改了这篇笔记，两份内容都已保留，不会被覆盖。",
                    style = AppTypography.bodySmall,
                    color = AppColors.textTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(14.dp))
                SecondaryButton(text = "查看冲突", onClick = onOpenConflict)
            }
        }

        // 技术详情（默认折叠；§56：不出现 Pull/Push）
        Column(
            Modifier.padding(
                start = AppSpacing.screenPaddingHorizontal,
                end = AppSpacing.screenPaddingHorizontal,
                top = 8.dp,
                bottom = 26.dp,
            ),
        ) {
            Row(
                modifier = Modifier
                    .clickable { techExpanded = !techExpanded }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(AppIcons.Info, contentDescription = null, tint = AppColors.textTertiary, modifier = Modifier.size(13.dp))
                Text("技术详情", style = AppTypography.caption, color = AppColors.textTertiary)
            }
            if (techExpanded) {
                val m = meta
                Text(
                    text = buildString {
                        appendLine("Repository  ${state.settings?.repoId ?: "—"}")
                        appendLine("Branch      ${m?.branch ?: "—"}")
                        appendLine("Tree SHA    ${m?.treeSha?.take(12) ?: "—"}")
                        appendLine("Last Refresh  ${m?.lastRefreshAt?.let { Format.relativeTime(it) } ?: "—"}")
                        appendLine("Cache Size  ${state.cacheSizeLabel}")
                    },
                    style = AppTypography.codeBlock.copy(fontSize = AppTypography.codeInline.fontSize),
                    color = AppColors.textTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AppShapes.small)
                        .background(AppColors.surface)
                        .border(1.dp, AppColors.border, AppShapes.small)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
}
