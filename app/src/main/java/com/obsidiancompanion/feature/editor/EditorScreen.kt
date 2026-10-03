package com.obsidiancompanion.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.obsidiancompanion.feature.reader.ReaderBlockRenderer
import com.obsidiancompanion.feature.reader.ReaderLinkHandler
import com.obsidiancompanion.model.markdown.MdDocument
import com.obsidiancompanion.model.markdown.MdInline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 编辑器页（原型 scr-editor）：取消/保存 + 快捷输入条 + mono 源文本 */
@Composable
fun EditorScreen(
    notePath: String,
    restoreDraftOnLoad: Boolean = false,
    onLeave: () -> Unit,
    onOpenConflict: () -> Unit,
    onOpenToken: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    viewModel: EditorViewModel = viewModel(factory = AppGraph.editorViewModelFactory),
) {
    LaunchedEffect(notePath) { viewModel.load(notePath, restoreDraftOnLoad) }

    var showDiscardDialog by remember { mutableStateOf(false) }
    val saving = viewModel.saveState == EditorViewModel.SaveState.SAVING
    val clipboard = LocalClipboardManager.current

    // 数据安全：有修改时返回需确认（Phase 1 Discrepancy #6）；
    // 保存中（PUT 在飞行中）拦截系统返回 —— 半途离开会让远端/本地状态不确定
    BackHandler(enabled = viewModel.isDirty || saving || viewModel.canUndo || viewModel.canRedo) {
        if (saving) onShowSnackbar("正在保存，请稍候")
        else if (viewModel.isDirty) showDiscardDialog = true
        else viewModel.discardAndLeave(onLeave)
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            containerColor = AppColors.surface,
            title = { Text("如何处理当前修改？", style = AppTypography.bodyBase) },
            text = { Text("保留的内容可从首页草稿中心继续编辑，尚未上传到 GitHub。", style = AppTypography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; viewModel.keepAndLeave(onLeave) }) {
                    Text("保留草稿并退出", color = AppColors.accent)
                }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { showDiscardDialog = false; viewModel.discardAndLeave(onLeave) }) {
                        Text("丢弃修改", color = AppColors.danger)
                    }
                    TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
                }
            },
        )
    }

    // §21：上次未保存的暂存恢复提示（写入失败 / 冲突 / 崩溃恢复）
    // 点外部 / 系统返回只收起弹窗并保留草稿（下次进入仍会提示）；只有明确点「丢弃」才删除
    viewModel.pendingDraft?.let { draft ->
        ConfirmationDialog(
            title = "发现未保存的修改",
            message = "上次编辑的内容还没有保存到 GitHub（可能是保存失败或应用退出）。要恢复继续编辑吗？",
            confirmText = "恢复",
            dismissText = "丢弃",
            onConfirm = { viewModel.restoreDraft() },
            onDismiss = { viewModel.dismissDraft() },
            onDismissRequest = { viewModel.keepDraft() },
        )
    }

    // 保存失败兜底：内容已在本机草稿，提供「复制全文」出口；认证类错误附加「重新设置 Token」（§25/§26）
    viewModel.saveFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSaveFailure,
            containerColor = AppColors.surface,
            shape = AppShapes.medium,
            title = { Text(if (failure.locallySaved) "无法保存到 GitHub" else "无法保存本机草稿", style = AppTypography.bodyBase) },
            text = {
                Text(
                    failure.message + if (failure.locallySaved) "\n\n当前修改已保留在本机草稿中。" else "",
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
                    if (viewModel.isDirty) showDiscardDialog = true
                    else if (viewModel.canUndo || viewModel.canRedo || viewModel.draftStaged) viewModel.discardAndLeave(onLeave)
                    else onLeave()
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
            EditorToolButton(label = "撤销", onClick = viewModel::undo, enabled = toolsEnabled && viewModel.canUndo)
            EditorToolButton(label = "重做", onClick = viewModel::redo, enabled = toolsEnabled && viewModel.canRedo)
            EditorToolButton(label = "H", onClick = viewModel::applyHeading, enabled = toolsEnabled)
            EditorToolButton(label = "B", bold = true, onClick = viewModel::applyBold, enabled = toolsEnabled)
            EditorToolIcon(icon = AppIcons.List, description = "列表", onClick = viewModel::applyListItem, enabled = toolsEnabled)
            EditorToolIcon(icon = AppIcons.Task, description = "任务", onClick = viewModel::applyTask, enabled = toolsEnabled)
            EditorToolButton(label = "[[ ]]", mono = true, onClick = viewModel::applyWikiLink, enabled = toolsEnabled)
        }
        AppHorizontalDivider()

        if (viewModel.preview && viewModel.loaded) {
            EditorPreview(viewModel.value.text, notePath, Modifier.weight(1f))
        } else Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .background(AppColors.surface)
                .padding(horizontal = AppSpacing.readerPaddingHorizontal, vertical = 16.dp),
        ) {
            val hint = viewModel.loadHint
            if (hint != null && viewModel.loaded) Text(hint, style = AppTypography.caption, color = AppColors.textTertiary)
            when {
                // 保存中禁用输入：保存的是发起时的快照，飞行中新敲的字不会进 PUT 也不会进 draft
                viewModel.loaded -> BasicTextField(
                    value = viewModel.value,
                    onValueChange = viewModel::onValueChange,
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
 * 独立 LazyColumn 只组合可见块，避免长笔记一次渲染所有段落和图片。
 */
@Composable
private fun EditorPreview(markdown: String, notePath: String, modifier: Modifier) {
    // Compose 1.7.6 lint 未识别下方 value 赋值，仅豁免此调用。
    @Suppress("ProduceStateDoesNotAssignValue")
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
    LazyColumn(
        modifier = modifier.fillMaxWidth().background(AppColors.surface),
        contentPadding = PaddingValues(horizontal = AppSpacing.readerPaddingHorizontal, vertical = 16.dp),
    ) {
        itemsIndexed(doc.blocks) { _, block ->
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
