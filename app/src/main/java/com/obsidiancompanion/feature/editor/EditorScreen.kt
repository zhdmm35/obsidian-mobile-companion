package com.obsidiancompanion.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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
import com.obsidiancompanion.core.ui.AppHorizontalDivider
import com.obsidiancompanion.core.ui.AppIconButton
import com.obsidiancompanion.core.ui.ConfirmationDialog
import com.obsidiancompanion.core.ui.GhostButton
import com.obsidiancompanion.core.ui.PrimaryButton
import com.obsidiancompanion.core.ui.SecondaryButton
import com.obsidiancompanion.data.markdown.MarkdownParser
import com.obsidiancompanion.data.metadata.entities.PendingEditEntity
import com.obsidiancompanion.data.repository.NoteOpenResult
import com.obsidiancompanion.data.repository.NoteSaveResult
import com.obsidiancompanion.feature.reader.ReaderBlockRenderer
import com.obsidiancompanion.feature.reader.ReaderLinkHandler
import com.obsidiancompanion.model.DomainError
import com.obsidiancompanion.model.markdown.MdDocument
import com.obsidiancompanion.model.markdown.MdInline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 编辑器（Phase 5 正式态）：真实 Markdown source + 保存写回 GitHub。
 * 保存状态机（§14）：Editing / Saving（按钮 disabled，防连点产生多个 Commit）。
 * 进入时冻结 baseSha + initialContent 为本次编辑 session 的 base（§39）；
 * 后台 Tree refresh 绝不替换正在编辑的文本（§39/§40），并发交给保存时的 SHA 判断（§15）。
 */
class EditorViewModel : ViewModel() {

    /** §14：Editor 至少 Editing / Saving；成功走回调（snackbar+导航），失败走 saveFailure 状态对话框。 */
    enum class SaveState { EDITING, SAVING }

    var value by mutableStateOf(TextFieldValue(""))
    var loadHint by mutableStateOf<String?>(null)
        private set
    var saveState by mutableStateOf(SaveState.EDITING)
        private set

    /** §21：检测到上次未保存的暂存（写入失败 / 冲突 / 崩溃）→ 提示恢复。 */
    var pendingDraft by mutableStateOf<PendingEditEntity?>(null)
        private set

    /** 保存失败兜底（§23/§25/§26/§28）：对话框提供「复制全文」出口；auth=true 时附加「重新设置 Token」。 */
    data class SaveFailure(val message: String, val auth: Boolean = false)
    var saveFailure by mutableStateOf<SaveFailure?>(null)
        private set

    fun dismissSaveFailure() { saveFailure = null }

    /** 自动暂存指示：内容已写入本机草稿（与「已保存到 GitHub」严格区分，只声明本机事实）。 */
    var draftStaged by mutableStateOf(false)
        private set

    val isDirty: Boolean get() = value.text != original
    private var original: String = ""
    private var baseSha: String? = null
    private var loadedPath: String? = null

    /** 加载完成前输入框禁用 —— 防止用户在 load 完成前打字被加载结果覆盖。 */
    var loaded by mutableStateOf(false)
        private set

    /** 预览模式：只读渲染当前文本（复用 Reader 渲染管线），不与输入框同时显示。 */
    var preview by mutableStateOf(false)

    fun load(path: String) {
        if (loadedPath == path) return
        loadedPath = path
        fetch(path)
    }

    /** 加载失败（离线未缓存 / 出错）后的重试入口。 */
    fun retry() {
        loadedPath?.let(::fetch)
    }

    private fun fetch(path: String) {
        viewModelScope.launch {
            loadHint = null
            when (val r = AppGraph.noteRepository.openNote(path)) {
                is NoteOpenResult.Content -> {
                    // §39：冻结 base —— 之后 Tree refresh 不影响本次编辑
                    baseSha = r.entry.blobSha
                    original = r.markdown
                    value = TextFieldValue(r.markdown, selection = TextRange(r.markdown.length))
                    loaded = true
                    pendingDraft = AppGraph.noteRepository.getPendingEdit(path)
                    startAutoDraft(path)
                }
                is NoteOpenResult.OfflineNotCached -> {
                    original = ""
                    loadHint = "还没有这篇笔记的缓存，联网加载一次后即可编辑"
                }
                is NoteOpenResult.Error -> {
                    original = ""
                    loadHint = "正文加载失败，请重试"
                }
            }
        }
    }

    /**
     * 恢复暂存内容：base 连同 draft 一起恢复为 [PendingEditEntity.baseSha]（草稿真正基于的版本）。
     * 绝不用当前 entry SHA 充当 base —— 那会让远端已分叉的旧草稿绕过 stale-SHA 检测、
     * 静默覆盖远端新修改（§15）；分叉时应走 409 → ConflictFlow，由用户明确选择。
     */
    fun restoreDraft() {
        val draft = pendingDraft ?: return
        baseSha = draft.baseSha
        value = TextFieldValue(draft.content, selection = TextRange(draft.content.length))
        pendingDraft = null
    }

    /** 放弃恢复：明确丢弃暂存。 */
    fun dismissDraft() {
        pendingDraft = null
        val path = loadedPath ?: return
        viewModelScope.launch { AppGraph.noteRepository.clearPendingEdit(path) }
    }

    /**
     * 保存（§6-§15）：
     * 成功 → onSaved（snackbar「已保存到 GitHub」+ 返回 Reader，Reader 经 Room 观察立即显示新内容）；
     * 409 → onConflict（draft 已保留，进入 ConflictScreen）；
     * 失败 → saveFailure 对话框（保留内容 + 复制全文出口），留在 Editor（§23/§26/§28）。
     */
    fun save(onSaved: () -> Unit, onConflict: () -> Unit, onMessage: (String) -> Unit) {
        val path = loadedPath ?: return
        val sha = baseSha ?: return
        if (!isDirty) { onSaved(); return } // 无修改：直接返回（§22）
        if (saveState == SaveState.SAVING) return // 防连点（§14）
        if (!AppGraph.network.isOnline) {
            // §23：不做离线队列；内容已在草稿里，对话框给复制出口，联网后再点保存
            saveFailure = SaveFailure("当前没有网络连接，无法保存到 GitHub")
            return
        }
        saveState = SaveState.SAVING
        viewModelScope.launch {
            when (val r = AppGraph.noteRepository.saveNote(path, sha, value.text)) {
                is NoteSaveResult.Saved -> {
                    saveState = SaveState.EDITING
                    original = value.text // 已保存内容成为新基准：迟到的防抖发射走 clear 分支
                    draftStaged = false
                    // §13：不阻塞 UI 的异步整树收敛
                    AppGraph.appScope.launch { AppGraph.indexRepository.refreshTree() }
                    onMessage("已保存到 GitHub") // 只声明可核实的事实（PUT 成功）
                    onSaved()
                }
                is NoteSaveResult.Conflict -> {
                    saveState = SaveState.EDITING
                    onConflict() // draft 已保留（§21）
                }
                is NoteSaveResult.Error -> {
                    saveState = SaveState.EDITING
                    saveFailure = when (r.error) {
                        DomainError.Unauthorized ->
                            SaveFailure("GitHub 登录信息已失效", auth = true) // §26
                        DomainError.Forbidden ->
                            SaveFailure("当前 GitHub Token 没有保存笔记的权限", auth = true) // §25
                        DomainError.NotFound ->
                            SaveFailure("这篇笔记已经不存在于 GitHub") // §28
                        DomainError.NetworkUnavailable ->
                            SaveFailure("当前没有网络连接，无法保存到 GitHub") // §23
                        DomainError.RateLimited ->
                            SaveFailure("GitHub 接口限流，请稍后再试")
                        else ->
                            SaveFailure("保存失败，请稍后再试")
                    }
                }
            }
        }
    }

    /** 明确放弃修改时清掉暂存（用户已确认不要了）。 */
    fun discardAndLeave(onLeave: () -> Unit) {
        autoDraftJob?.cancel() // 先停防抖，防止离开瞬间迟到的写入把刚清掉的草稿复活
        val path = loadedPath
        viewModelScope.launch { if (path != null) AppGraph.noteRepository.clearPendingEdit(path) }
        onLeave()
    }

    /**
     * 编辑中自动暂存（§21 扩展）：停笔 1.5s 后写入 pending_edits —— 杀进程 / 切后台不丢内容。
     * 改回原文时清除草稿（无未保存内容）；保存成功后 original 前移，
     * 迟到的防抖发射只会命中 clear 分支，不会把刚保存的内容复活成草稿。
     */
    @OptIn(FlowPreview::class)
    private fun startAutoDraft(path: String) {
        autoDraftJob?.cancel()
        autoDraftJob = viewModelScope.launch {
            snapshotFlow { value.text }
                .drop(1) // 加载完成的初值不落草稿
                .debounce(1500)
                .collect { text ->
                    val sha = baseSha ?: return@collect
                    if (saveState == SaveState.SAVING) return@collect // 保存飞行中输入已禁用，防御
                    if (text == original) {
                        AppGraph.noteRepository.clearPendingEdit(path)
                        draftStaged = false
                    } else {
                        AppGraph.noteRepository.stagePendingEdit(path, sha, text)
                        draftStaged = true
                    }
                }
        }
    }

    private var autoDraftJob: Job? = null

    private fun wrap(before: String, after: String) {
        val v = value
        val r = EditorTextOps.wrap(v.text, v.selection.min, v.selection.max, before, after)
        value = TextFieldValue(r.text, TextRange(r.cursor))
    }

    private fun linePrefix(prefix: String) {
        val v = value
        val r = EditorTextOps.linePrefix(v.text, v.selection.min, prefix)
        value = TextFieldValue(r.text, TextRange(r.cursor))
    }

    fun applyHeading() = linePrefix("## ")
    fun applyBold() = wrap("**", "**")
    fun applyListItem() = linePrefix("- ")
    fun applyTask() = linePrefix("- [ ] ")
    fun applyWikiLink() = wrap("[[", "]]")
}

/** 编辑器页（原型 scr-editor）：取消/保存 + 快捷输入条 + mono 源文本 */
@Composable
fun EditorScreen(
    notePath: String,
    onLeave: () -> Unit,
    onOpenConflict: () -> Unit,
    onOpenToken: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    viewModel: EditorViewModel = viewModel(),
) {
    LaunchedEffect(notePath) { viewModel.load(notePath) }

    var showDiscardDialog by remember { mutableStateOf(false) }
    val saving = viewModel.saveState == EditorViewModel.SaveState.SAVING
    val clipboard = LocalClipboardManager.current

    // 数据安全：有修改时返回需确认（Phase 1 Discrepancy #6）；
    // 保存中（PUT 在飞行中）拦截系统返回 —— 半途离开会让远端/本地状态不确定
    BackHandler(enabled = viewModel.isDirty || saving) {
        if (saving) onShowSnackbar("正在保存，请稍候") else showDiscardDialog = true
    }

    if (showDiscardDialog) {
        ConfirmationDialog(
            title = "放弃未保存的修改？",
            message = "当前修改尚未保存，放弃后将无法找回。",
            confirmText = "放弃修改",
            onConfirm = {
                showDiscardDialog = false
                viewModel.discardAndLeave(onLeave)
            },
            onDismiss = { showDiscardDialog = false },
        )
    }

    // §21：上次未保存的暂存恢复提示（写入失败 / 冲突 / 崩溃恢复）
    viewModel.pendingDraft?.let { draft ->
        ConfirmationDialog(
            title = "发现未保存的修改",
            message = "上次编辑的内容还没有保存到 GitHub（可能是保存失败或应用退出）。要恢复继续编辑吗？",
            confirmText = "恢复",
            dismissText = "丢弃",
            onConfirm = { viewModel.restoreDraft() },
            onDismiss = { viewModel.dismissDraft() },
        )
    }

    // 保存失败兜底：内容已在本机草稿，提供「复制全文」出口；认证类错误附加「重新设置 Token」（§25/§26）
    viewModel.saveFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSaveFailure,
            containerColor = AppColors.surface,
            shape = AppShapes.medium,
            title = { Text("无法保存到 GitHub", style = AppTypography.bodyBase) },
            text = {
                Text(
                    failure.message + "\n\n当前修改已保留在本机草稿中，不会丢失。",
                    style = AppTypography.bodySmall,
                    color = AppColors.textTertiary,
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (failure.auth) {
                        TextButton(onClick = {
                            viewModel.dismissSaveFailure()
                            onOpenToken()
                        }) {
                            Text("重新设置 Token", color = AppColors.accent)
                        }
                    }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(viewModel.value.text))
                        viewModel.dismissSaveFailure()
                        onShowSnackbar("全文已复制，可粘贴到其它应用")
                    }) {
                        Text("复制全文", color = AppColors.accent)
                    }
                    TextButton(onClick = viewModel::dismissSaveFailure) {
                        Text("继续编辑", color = AppColors.textTertiary)
                    }
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：取消 / 保存
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton(
                text = "取消",
                onClick = {
                    if (saving) return@GhostButton
                    if (viewModel.isDirty) showDiscardDialog = true else onLeave()
                },
                small = true,
            )
            Spacer(Modifier.weight(1f))
            // 两级状态只声明本机事实：自动暂存成功显示「已暂存本机」；「已保存到 GitHub」仅在 PUT 成功的 snackbar
            if (viewModel.draftStaged && viewModel.isDirty) {
                Text("已暂存本机", style = AppTypography.caption, color = AppColors.textMeta)
                Spacer(Modifier.width(10.dp))
            }
            // 预览切换（仅加载完成后可用；激活时 accent 着色）
            if (viewModel.loaded) {
                AppIconButton(
                    icon = AppIcons.Eye,
                    contentDescription = if (viewModel.preview) "返回编辑" else "预览",
                    onClick = { if (!saving) viewModel.preview = !viewModel.preview },
                    tint = if (viewModel.preview) AppColors.accent else AppColors.textPrimary,
                    iconSize = 19.dp,
                )
            }
            PrimaryButton(
                text = if (saving) "保存中…" else "保存", // §14：Saving 简单 loading
                onClick = {
                    viewModel.save(
                        onSaved = onLeave,
                        onConflict = onOpenConflict,
                        onMessage = onShowSnackbar,
                    )
                },
                enabled = !saving, // §14：防连点产生多个 Commit
                small = true,
            )
        }

        // 快捷输入条（原型 .edtb）；未加载完成 / 保存中 / 预览中不可用（与输入框同一把锁）
        val toolsEnabled = viewModel.loaded && !saving && !viewModel.preview
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EditorToolButton(label = "H", onClick = viewModel::applyHeading, enabled = toolsEnabled)
            EditorToolButton(label = "B", bold = true, onClick = viewModel::applyBold, enabled = toolsEnabled)
            EditorToolIcon(icon = AppIcons.List, description = "列表", onClick = viewModel::applyListItem, enabled = toolsEnabled)
            EditorToolIcon(icon = AppIcons.Task, description = "任务", onClick = viewModel::applyTask, enabled = toolsEnabled)
            EditorToolButton(label = "[[ ]]", mono = true, onClick = viewModel::applyWikiLink, enabled = toolsEnabled)
        }
        AppHorizontalDivider()

        // 源文本
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .background(AppColors.surface)
                .padding(horizontal = AppSpacing.readerPaddingHorizontal, vertical = 16.dp),
        ) {
            val hint = viewModel.loadHint
            when {
                // 预览：只读渲染当前文本（预览中文本不可变，每次进入只解析一次）
                viewModel.preview && viewModel.loaded -> EditorPreview(
                    markdown = viewModel.value.text,
                    notePath = notePath,
                )
                // 保存中禁用输入：保存的是发起时的快照，飞行中新敲的字不会进 PUT 也不会进 draft
                viewModel.loaded -> BasicTextField(
                    value = viewModel.value,
                    onValueChange = { viewModel.value = it },
                    enabled = !saving,
                    textStyle = AppTypography.editorSource.copy(color = AppColors.textPrimary),
                    cursorBrush = SolidColor(AppColors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 240.dp),
                )
                hint != null -> Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(hint, style = AppTypography.bodySmall, color = AppColors.textTertiary)
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton(text = "重试", onClick = viewModel::retry, small = true)
                }
                else -> Text("正在加载正文…", style = AppTypography.bodySmall, color = AppColors.textTertiary)
            }
        }
    }
}

@Composable
private fun EditorToolButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    bold: Boolean = false,
    mono: Boolean = false,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 42.dp, minHeight = 42.dp)
            .clip(AppShapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = AppTypography.codeInline,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (enabled) AppColors.textSecondary else AppColors.textTertiary,
        )
    }
}

@Composable
private fun EditorToolIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(AppShapes.small)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) AppColors.textSecondary else AppColors.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 编辑预览：当前文本的一次性只读渲染（复用 Reader 渲染管线）。
 * 预览中文本不可变 → produceState 每次进入只解析一次；链接/图片点击在预览中惰性（不跳转）。
 * 外层容器已是 verticalScroll（与源文本同一滚动区），故用普通 Column 而非 LazyColumn。
 */
@Composable
private fun EditorPreview(markdown: String, notePath: String) {
    val document by produceState<MdDocument?>(null, markdown) {
        value = withContext(Dispatchers.Default) { MarkdownParser.parse(markdown) }
    }
    val links = remember {
        object : ReaderLinkHandler {
            override fun onExternalLink(url: String) = Unit
            override fun onWikiLink(wiki: MdInline.WikiLink) = Unit
        }
    }
    val doc = document
    if (doc == null) {
        Text("正在生成预览…", style = AppTypography.bodySmall, color = AppColors.textTertiary)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        doc.blocks.forEach { block ->
            ReaderBlockRenderer(
                block = block,
                links = links,
                currentNotePath = notePath,
                deadLinks = emptySet(),
                onOpenImage = {},
            )
        }
    }
}
