package com.obsidiancompanion.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.feature.files.NoteTemplatePicker
import com.obsidiancompanion.feature.files.NoteTemplatePreview

@Composable
fun DailyNoteDialog(viewModel: DailyNoteViewModel, onReady: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    val enabled = !viewModel.loading && !viewModel.inFlight
    AlertDialog(
        onDismissRequest = { if (!viewModel.inFlight) onDismiss() },
        containerColor = AppColors.surface,
        shape = AppShapes.medium,
        title = { Text("今日笔记", style = AppTypography.bodyBase) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (viewModel.loading) Text("正在读取日记设置…", style = AppTypography.bodySmall)
                else {
                    Text(viewModel.repoId ?: "尚未连接仓库", style = AppTypography.caption, color = AppColors.textTertiary)
                    Text("日期：${viewModel.date} · 按手机本地日期", style = AppTypography.caption, color = AppColors.textTertiary)
                    Text("日记目录（留空为根目录）", style = AppTypography.caption, color = AppColors.textTertiary)
                    BasicTextField(
                        value = viewModel.folder,
                        onValueChange = viewModel::onFolderChange,
                        enabled = enabled,
                        singleLine = true,
                        textStyle = AppTypography.bodyBase.copy(color = AppColors.textPrimary),
                        cursorBrush = SolidColor(AppColors.accent),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(AppShapes.medium)
                                .background(AppColors.background).border(1.dp, AppColors.borderStrong, AppShapes.medium).padding(12.dp)) {
                                if (viewModel.folder.isEmpty()) Text("如 日记 或 Journal/Daily", style = AppTypography.bodySmall, color = AppColors.textMeta)
                                inner()
                            }
                        },
                    )
                    Text("目标：${viewModel.path ?: "目录不可用"}", style = AppTypography.caption, color = AppColors.textSecondary)
                    Text("已有笔记会直接打开；模板只用于新建。目录和模板按仓库记忆。", style = AppTypography.caption, color = AppColors.textTertiary)
                    NoteTemplatePicker(viewModel.template, enabled, viewModel::onTemplateChange)
                    NoteTemplatePreview(viewModel.preview)
                }
                viewModel.error?.let { Text(it, style = AppTypography.caption, color = AppColors.danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.open(onReady) }, enabled = enabled && viewModel.repoId != null) {
                Text(if (viewModel.inFlight) "正在打开…" else "打开或创建", color = AppColors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !viewModel.inFlight) { Text("取消", color = AppColors.textTertiary) }
        },
    )
}
