package com.obsidiancompanion.feature.sync

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
import com.obsidiancompanion.core.ui.ConfirmationDialog
import com.obsidiancompanion.core.ui.EmptyState
import com.obsidiancompanion.core.ui.PrimaryButton
import com.obsidiancompanion.core.ui.SecondaryButton
import com.obsidiancompanion.data.mock.ConflictDemo
import com.obsidiancompanion.data.repository.NoteRepository
import com.obsidiancompanion.data.repository.NoteSaveResult
import com.obsidiancompanion.model.DomainError
import kotlinx.coroutines.launch

/**
 * 冲突处理（Phase 5 §15-§18/§53）：保存时 base SHA 过期（远端在编辑期间被修改）后进入。
 * 我的修改 = PendingEdit 暂存（保存前写入，崩溃也安全）；GitHub 版本 = 实时拉取的远端最新。
 * 不做 diff / 3-way merge / 自动覆盖；只有两个用户明确操作：使用我的修改 / 使用 GitHub 版本。
 */
class ConflictViewModel : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Ready(
            val path: String,
            val title: String,
            val folder: String,
            val mine: String,
            val remote: String,
            val resolving: Boolean = false,
        ) : State
        data class Error(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    private var loadedPath: String? = null

    fun load(path: String) {
        if (loadedPath == path && state is State.Ready) return
        loadedPath = path
        fetch(path)
    }

    private fun fetch(path: String) {
        state = State.Loading
        viewModelScope.launch {
            val draft = AppGraph.noteRepository.getPendingEdit(path)
            if (draft == null) {
                state = State.Error("没有找到待解决的修改，可能已经处理过了")
                return@launch
            }
            when (val r = AppGraph.noteRepository.fetchRemoteVersion(path)) {
                is NoteRepository.RemoteNoteVersion.Ready -> state = State.Ready(
                    path = path,
                    title = path.substringAfterLast('/').removeSuffix(".md"),
                    folder = path.substringBeforeLast('/', "").ifEmpty { "根目录" },
                    mine = draft.content,
                    remote = r.content,
                )
                is NoteRepository.RemoteNoteVersion.Error -> state = State.Error(
                    when (r.error) {
                        DomainError.NotFound -> "这篇笔记已经不存在于 GitHub"
                        DomainError.Unauthorized -> "GitHub 登录信息已失效，请在设置中重新设置 Token"
                        DomainError.NetworkUnavailable -> "网络不可用，请检查网络后重试"
                        else -> "无法获取 GitHub 最新版本，请稍后再试"
                    },
                )
            }
        }
    }

    /** §18「使用我的修改」：用户明确确认的覆盖（UI 已弹确认，§54）。 */
    fun useMine(onResolved: () -> Unit, onMessage: (String) -> Unit) {
        val ready = state as? State.Ready ?: return
        if (ready.resolving) return
        if (!AppGraph.network.isOnline) { onMessage("当前没有网络连接"); return }
        state = ready.copy(resolving = true)
        viewModelScope.launch {
            when (val r = AppGraph.noteRepository.resolveConflictUseMine(ready.path, ready.mine)) {
                is NoteSaveResult.Saved -> {
                    AppGraph.appScope.launch { AppGraph.indexRepository.refreshTree() }
                    onMessage("已保存")
                    onResolved()
                }
                is NoteSaveResult.Conflict -> {
                    // 二次冲突：远端在我们处理期间又变了 —— 仍不覆盖，重新拉两个版本
                    onMessage("GitHub 上又有新修改，已重新加载两个版本")
                    fetch(ready.path)
                }
                is NoteSaveResult.Error -> {
                    state = ready.copy(resolving = false)
                    onMessage(
                        when (r.error) {
                            DomainError.NetworkUnavailable -> "当前没有网络连接"
                            DomainError.NotFound -> "这篇笔记已经不存在于 GitHub"
                            DomainError.Unauthorized -> "GitHub 登录信息已失效，请在设置中重新设置 Token"
                            DomainError.Forbidden -> "当前 GitHub Token 没有保存笔记的权限"
                            else -> "保存失败，请稍后再试（修改仍保留在本机）"
                        },
                    )
                }
            }
        }
    }

    /** §18「使用 GitHub 版本」：放弃手机当前修改。 */
    fun useRemote(onResolved: () -> Unit, onMessage: (String) -> Unit) {
        val ready = state as? State.Ready ?: return
        if (ready.resolving) return
        if (!AppGraph.network.isOnline) { onMessage("当前没有网络连接"); return }
        state = ready.copy(resolving = true)
        viewModelScope.launch {
            when (val r = AppGraph.noteRepository.resolveConflictUseRemote(ready.path)) {
                is NoteSaveResult.Saved -> {
                    AppGraph.appScope.launch { AppGraph.indexRepository.refreshTree() }
                    onMessage("已使用 GitHub 版本")
                    onResolved()
                }
                is NoteSaveResult.Error -> {
                    state = ready.copy(resolving = false)
                    onMessage(
                        when (r.error) {
                            DomainError.NetworkUnavailable -> "当前没有网络连接"
                            else -> "操作失败，请稍后再试"
                        },
                    )
                }
                is NoteSaveResult.Conflict -> fetch(ready.path) // 不会发生（无 PUT）；防御分支
            }
        }
    }
}

/**
 * 冲突处理页（§53 文案）。notePath == null → DEBUG 演示模式（ConflictDemo 数据，按钮只结束演示）。
 */
@Composable
fun ConflictScreen(
    notePath: String?,
    onBack: () -> Unit,
    onResolved: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    viewModel: ConflictViewModel = viewModel(),
) {
    val demo = notePath == null
    if (!demo) {
        LaunchedEffect(notePath) { viewModel.load(notePath!!) }
    }

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showOverwriteConfirm by remember { mutableStateOf(false) }

    val demoReady = remember(demo) {
        if (demo) {
            ConflictViewModel.State.Ready(
                path = "工作/AI/Agent设计.md",
                title = "Agent设计",
                folder = "工作 / AI",
                mine = ConflictDemo.LOCAL,
                remote = ConflictDemo.REMOTE,
            )
        } else {
            null
        }
    }
    val state = demoReady ?: viewModel.state

    // 解决中（覆盖 PUT / 拉取远端在飞行中）：拦截返回，避免半途取消导致远端/本地状态不确定
    val resolving = state is ConflictViewModel.State.Ready && state.resolving
    BackHandler(enabled = resolving) { }

    if (showOverwriteConfirm) {
        ConfirmationDialog(
            title = "使用我的修改",
            message = "确定使用手机上的修改覆盖 GitHub 最新版本？", // §54
            confirmText = "确定覆盖",
            onConfirm = {
                showOverwriteConfirm = false
                if (demo) {
                    ConflictDemo.enabled = false
                    onShowSnackbar("演示结束")
                    onResolved()
                } else {
                    viewModel.useMine(onResolved, onShowSnackbar)
                }
            },
            onDismiss = { showOverwriteConfirm = false },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = AppSpacing.screenBottomPadding),
    ) {
        BackTopBar(title = "冲突处理", onBack = { if (!resolving) onBack() })

        when (state) {
            is ConflictViewModel.State.Loading -> Column(
                Modifier.fillMaxWidth().padding(top = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SpinningIcon(AppIcons.Refresh, tint = AppColors.textMeta, size = 22.dp, contentDescription = null)
                Spacer(Modifier.height(10.dp))
                Text("正在获取两个版本……", style = AppTypography.bodySmall, color = AppColors.textTertiary)
            }

            is ConflictViewModel.State.Error -> Column(
                Modifier.fillMaxWidth().padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyState(icon = AppIcons.Alert, title = "无法打开冲突处理", subtitle = state.message)
                SecondaryButton(text = "重试", onClick = { if (!demo) notePath?.let(viewModel::load) })
            }

            is ConflictViewModel.State.Ready -> ConflictReadyContent(
                state = state,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onUseMine = { showOverwriteConfirm = true },
                onUseRemote = {
                    if (demo) {
                        ConflictDemo.enabled = false
                        onShowSnackbar("演示结束")
                        onResolved()
                    } else {
                        viewModel.useRemote(onResolved, onShowSnackbar)
                    }
                },
            )
        }
    }
}

@Composable
private fun ConflictReadyContent(
    state: ConflictViewModel.State.Ready,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onUseMine: () -> Unit,
    onUseRemote: () -> Unit,
) {
    // 说明卡（§53 文案）
    ConflictCard {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Icon(AppIcons.Shield, contentDescription = null, tint = AppColors.textSecondary, modifier = Modifier.size(20.dp))
            Column {
                Text("发现新的修改", style = AppTypography.embedTitle.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    "这篇笔记在你编辑期间已经被其他设备修改。请选择要保留的版本。",
                    style = AppTypography.bodySmall,
                    color = AppColors.textTertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    // 冲突文件卡
    ConflictCard {
        Text("${state.title}.md", style = AppTypography.titleSmall)
        Text(state.folder, style = AppTypography.caption, color = AppColors.textTertiary, modifier = Modifier.padding(top = 3.dp))
    }

    // 版本 Tab（§53：我的修改 / GitHub 版本）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.screenPaddingHorizontal,
                end = AppSpacing.screenPaddingHorizontal,
                top = 10.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VersionTab(label = "我的修改", selected = selectedTab == 0, onClick = { onSelectTab(0) }, modifier = Modifier.weight(1f))
        VersionTab(label = "GitHub 版本", selected = selectedTab == 1, onClick = { onSelectTab(1) }, modifier = Modifier.weight(1f))
    }

    // 版本预览（mono / 暖沙底 / 限高滚动）
    Text(
        text = if (selectedTab == 0) state.mine else state.remote,
        style = AppTypography.codeInline,
        color = AppColors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.screenPaddingHorizontal,
                end = AppSpacing.screenPaddingHorizontal,
                top = 10.dp,
            )
            .clip(AppShapes.small)
            .background(AppColors.surfaceWarm)
            .padding(AppSpacing.lg)
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
    )

    // 两个明确操作（§18）；resolving 时禁用防重复提交
    PrimaryButton(
        text = if (state.resolving) "正在处理…" else "使用我的修改",
        onClick = onUseMine,
        enabled = !state.resolving,
        block = true,
        modifier = Modifier.padding(
            start = AppSpacing.screenPaddingHorizontal,
            end = AppSpacing.screenPaddingHorizontal,
            top = 16.dp,
        ),
    )
    SecondaryButton(
        text = "使用 GitHub 版本",
        onClick = onUseRemote,
        block = true,
        modifier = Modifier.padding(
            start = AppSpacing.screenPaddingHorizontal,
            end = AppSpacing.screenPaddingHorizontal,
            top = 10.dp,
        ),
    )
}

/** 冲突页卡片：surface 底 + 边框 + 屏幕左右 padding（说明卡 / 冲突文件卡共用）。 */
@Composable
private fun ConflictCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.screenPaddingHorizontal,
                end = AppSpacing.screenPaddingHorizontal,
                top = AppSpacing.md,
            )
            .clip(AppShapes.small)
            .background(AppColors.surface)
            .border(1.dp, AppColors.border, AppShapes.small)
            .padding(AppSpacing.lg),
        content = content,
    )
}

@Composable
private fun VersionTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(AppShapes.small)
            .background(if (selected) AppColors.textPrimary else AppColors.surface)
            .border(1.dp, if (selected) AppColors.textPrimary else AppColors.borderStrong, AppShapes.small)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            style = AppTypography.tableText,
            color = if (selected) AppColors.surface else AppColors.textTertiary,
        )
    }
}
