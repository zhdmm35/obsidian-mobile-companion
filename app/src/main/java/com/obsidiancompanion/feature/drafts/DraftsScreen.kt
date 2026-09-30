package com.obsidiancompanion.feature.drafts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.BackTopBar
import com.obsidiancompanion.core.ui.ConfirmationDialog
import com.obsidiancompanion.core.ui.EmptyState
import com.obsidiancompanion.core.ui.GhostButton
import com.obsidiancompanion.data.capture.CaptureDraftStore
import com.obsidiancompanion.data.metadata.entities.PendingEditEntity
import com.obsidiancompanion.data.metadata.entities.PendingEditSummary
import com.obsidiancompanion.data.metadata.entities.CaptureDraftSummary
import com.obsidiancompanion.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class DraftsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val repoId: String? = null,
    val edits: List<PendingEditSummary> = emptyList(),
    val shares: List<CaptureDraftSummary> = emptyList(),
)

class DraftsViewModel : ViewModel() {
    private val dao = AppGraph.database.pendingEditDao()
    val state = combine(AppGraph.settings.flow, dao.observeSummaries(), AppGraph.captureDraft.drafts) { settings, edits, shares ->
        DraftsUiState(loading = false, repoId = settings.repoId, edits = edits, shares = shares)
    }.catch { emit(DraftsUiState(loading = false, error = "草稿读取失败，原有数据仍保留，请返回后重试")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DraftsUiState())

    fun copy(edit: PendingEditSummary?, share: CaptureDraftSummary?, onCopied: (String) -> Unit, onMessage: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val text = if (edit != null) dao.get(edit.repoId, edit.path)?.content
                    else share?.let { AppGraph.captureDraft.find(it.id)?.text }
                if (text == null) onMessage("草稿已被删除，请刷新后重试") else onCopied(text)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onMessage("草稿操作失败，请稍后重试") }
        }
    }

    fun prepareDiscard(edit: PendingEditSummary?, share: CaptureDraftSummary?,
        onReady: (PendingEditEntity?, CaptureDraftStore.Draft?) -> Unit, onMessage: (String) -> Unit) {
        viewModelScope.launch {
            try {
                if (edit != null) {
                    val full = dao.get(edit.repoId, edit.path)
                    if (full != null && full.updatedAt == edit.updatedAt && full.content.startsWith(edit.preview)) onReady(full, null)
                    else onMessage("草稿已发生变化，请检查后重新操作")
                } else if (share != null) {
                    val full = AppGraph.captureDraft.find(share.id)
                    if (full != null && full.updatedAt == share.updatedAt && full.name == share.name && full.folder == share.folder) onReady(null, full)
                    else onMessage("草稿已发生变化，请检查后重新操作")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onMessage("草稿操作失败，请稍后重试") }
        }
    }

    fun discard(edit: PendingEditEntity?, share: CaptureDraftStore.Draft?, onMessage: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val removed = if (edit != null) {
                    dao.deleteIfUnchanged(edit.repoId, edit.path, edit.baseSha, edit.content, edit.updatedAt) > 0
                } else share != null && AppGraph.captureDraft.clearIfUnchanged(share)
                onMessage(if (removed) "草稿已丢弃" else "草稿已发生变化，请检查后重新操作")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onMessage("草稿操作失败，请稍后重试") }
        }
    }
}

@Composable
fun DraftsScreen(
    onBack: () -> Unit,
    onOpenEdit: (String) -> Unit,
    onOpenCapture: (String) -> Unit,
    onShowSnackbar: (String) -> Unit,
    viewModel: DraftsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    var deletingEdit by remember { mutableStateOf<PendingEditEntity?>(null) }
    var deletingShare by remember { mutableStateOf<CaptureDraftStore.Draft?>(null) }
    if (deletingEdit != null || deletingShare != null) {
        ConfirmationDialog(
            title = "丢弃这条草稿？",
            message = "仅删除本机未上传的内容，无法撤销。GitHub 中的笔记不会被删除。",
            confirmText = "丢弃草稿",
            onConfirm = {
                viewModel.discard(deletingEdit, deletingShare, onShowSnackbar)
                deletingEdit = null
                deletingShare = null
            },
            onDismiss = { deletingEdit = null; deletingShare = null },
        )
    }
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        onShowSnackbar("全文已复制")
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("草稿中心", onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("草稿只保存在这台设备，需手动保存才会上传 GitHub。撤销历史不会随草稿保存。",
                    style = AppTypography.bodySmall, color = AppColors.textTertiary)
            }
            if (state.loading) item { Text("正在读取草稿…", style = AppTypography.bodySmall) }
            else if (state.error != null) item { Text(state.error.orEmpty(), style = AppTypography.bodySmall, color = AppColors.danger) }
            else if (state.edits.isEmpty() && state.shares.isEmpty()) item {
                EmptyState(icon = AppIcons.Book, title = "还没有草稿", subtitle = "编辑笔记或分享内容到本应用后，可在这里继续处理")
            }
            if (state.edits.isNotEmpty()) item { Text("笔记编辑 · ${state.edits.size}", style = AppTypography.rowTitleSmall) }
            items(state.edits, key = { "edit:${it.repoId}:${it.path}" }) { draft ->
                DraftCard(
                    title = draft.path.substringAfterLast('/'),
                    detail = "${draft.repoId} · ${draft.path}",
                    text = draft.preview,
                    updatedAt = draft.updatedAt,
                    action = "继续编辑",
                    onContinue = {
                        if (draft.repoId == state.repoId) onOpenEdit(draft.path)
                        else onShowSnackbar("请先在设置中连接 ${draft.repoId}，也可以先复制草稿")
                    },
                    onCopy = { viewModel.copy(draft, null, ::copy, onShowSnackbar) },
                    onDiscard = { viewModel.prepareDiscard(draft, null, { e, share -> deletingEdit = e; deletingShare = share }, onShowSnackbar) },
                )
            }
            if (state.shares.isNotEmpty()) item { Text("分享收集 · ${state.shares.size}", style = AppTypography.rowTitleSmall) }
            items(state.shares, key = { "share:${it.id}" }) { draft ->
                DraftCard(
                    title = draft.name.ifBlank { "快速收集" },
                    detail = draft.folder.ifBlank { "根目录" },
                    text = draft.preview,
                    updatedAt = draft.updatedAt,
                    action = "继续处理",
                    onContinue = { onOpenCapture(draft.id) },
                    onCopy = { viewModel.copy(null, draft, ::copy, onShowSnackbar) },
                    onDiscard = { viewModel.prepareDiscard(null, draft, { e, share -> deletingEdit = e; deletingShare = share }, onShowSnackbar) },
                )
            }
            item { Text("", Modifier.padding(bottom = 16.dp)) }
        }
    }
}

@Composable
private fun DraftCard(title: String, detail: String, text: String, updatedAt: Long, action: String,
    onContinue: () -> Unit, onCopy: () -> Unit, onDiscard: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(AppColors.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = AppTypography.rowTitleSmall)
        Text("$detail · ${Format.relativeTime(updatedAt)}", style = AppTypography.caption, color = AppColors.textTertiary)
        Text(text.take(160).ifBlank { "（空正文）" }, maxLines = 3, style = AppTypography.bodySmall, color = AppColors.textSecondary)
        Row {
            GhostButton(action, onContinue, small = true)
            GhostButton("复制全文", onCopy, small = true)
            GhostButton("丢弃", onDiscard, small = true)
        }
    }
}
