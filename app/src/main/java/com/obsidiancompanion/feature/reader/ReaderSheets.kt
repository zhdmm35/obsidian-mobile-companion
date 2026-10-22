package com.obsidiancompanion.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.obsidiancompanion.core.ui.AppChip
import com.obsidiancompanion.data.settings.ReadingAppearance
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.BuildConfig
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.AppBottomSheet
import com.obsidiancompanion.core.ui.KeyValueRow
import com.obsidiancompanion.core.ui.SheetActionRow

@Composable
fun ReaderOutlineSheet(
    entries: List<ReaderOutlineEntry>,
    onDismiss: () -> Unit,
    onSelect: (ReaderOutlineEntry) -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    AppBottomSheet(onDismiss = onDismiss) {
        Text("目录", style = AppTypography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            items(entries, key = { it.blockIndex }) { entry ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = { onSelect(entry) })
                        .heightIn(min = 48.dp)
                        .padding(start = 20.dp + ((entry.level - 1) * 12).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        entry.title,
                        style = if (entry.level == 1) AppTypography.rowTitleSmall else AppTypography.bodyBase,
                        color = if (entry.level <= 2) AppColors.textPrimary else AppColors.textTertiary,
                    )
                }
            }
        }
    }
}

/**
 * Reader 更多菜单（原型 moreMenu）：编辑 / 收藏 / 文件信息 / 复制路径 / 分享笔记 / 刷新此笔记。
 * Phase 5 §4：「编辑」入口恢复，进入既有 EditorScreen（编辑已有 Markdown，不写 frontmatter）。
 */
@Composable
fun ReaderMoreSheet(
    noteTitle: String,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowFileInfo: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onRefreshNote: () -> Unit,
    onReadingSettings: (() -> Unit)? = null,
    onEditTasks: (() -> Unit)? = null,
) {
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                noteTitle,
                style = AppTypography.titleSmall,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp),
            )
            SheetActionRow(icon = AppIcons.Pencil, text = "编辑", onClick = onEdit)
            if (onEditTasks != null) SheetActionRow(icon = AppIcons.Task, text = "勾选任务", onClick = onEditTasks)
            if (onReadingSettings != null) SheetActionRow(icon = AppIcons.Sliders, text = "阅读设置", onClick = onReadingSettings)
            SheetActionRow(icon = AppIcons.Star, text = if (isFavorite) "取消收藏" else "收藏", onClick = onToggleFavorite)
            SheetActionRow(icon = AppIcons.Info, text = "查看文件信息", onClick = onShowFileInfo)
            SheetActionRow(icon = AppIcons.Copy, text = "复制文件路径", onClick = onCopyPath)
            SheetActionRow(icon = AppIcons.Share, text = "分享笔记", onClick = onShare)
            SheetActionRow(icon = AppIcons.Refresh, text = "刷新此笔记", onClick = onRefreshNote)
        }
    }
}

@Composable
fun ReaderAppearanceSheet(
    appearance: ReadingAppearance,
    onChange: (ReadingAppearance) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(appearance.normalized()) }
    fun update(next: ReadingAppearance) {
        selected = next.normalized()
        onChange(selected)
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    AppBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 20.dp)) {
            Text("阅读设置", style = AppTypography.titleSmall, modifier = Modifier.padding(bottom = 12.dp))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("字号 ${selected.fontSize}", style = AppTypography.bodyBase, modifier = Modifier.weight(1f))
                    TextButton(onClick = { update(selected.copy(fontSize = selected.fontSize - 1)) }, enabled = selected.fontSize > 14) {
                        Text("减小字号")
                    }
                    TextButton(onClick = { update(selected.copy(fontSize = selected.fontSize + 1)) }, enabled = selected.fontSize < 26) {
                        Text("增大字号")
                    }
                }
                Text("行距", style = AppTypography.bodyBase)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(85 to "紧凑", 100 to "标准", 115 to "宽松").forEach { (value, label) ->
                        AppChip(text = label, selected = selected.lineSpacing == value, onClick = { update(selected.copy(lineSpacing = value)) })
                    }
                }
                CompositionLocalProvider(LocalReadingAppearance provides selected) {
                    Text(
                        "阅读预览\n把笔记带在身边，随时阅读和记录。\n字号与行距会用于所有笔记。",
                        style = readerTextStyle(AppTypography.bodyReader),
                        modifier = Modifier.fillMaxWidth().clip(AppShapes.small).background(AppColors.surfaceWarm).padding(14.dp),
                    )
                }
                Text("仅调整本机阅读显示，不修改笔记内容。", style = AppTypography.caption, color = AppColors.textTertiary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { update(ReadingAppearance()) }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        }
    }
}

/** 文件信息 Sheet（原型 fileInfo）：真实 Tree 数据；SHA 仅 DEBUG 显示（§53）。 */
@Composable
fun FileInfoSheet(
    title: String,
    path: String,
    sizeLabel: String,
    blobSha: String,
    frontmatterRaw: String?,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(onDismiss = onDismiss) {
        Text(
            "文件信息",
            style = AppTypography.titleSmall,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp),
        )
        Column(Modifier.fillMaxWidth()) {
            KeyValueRow("文件名", "$title.md")
            KeyValueRow("路径", "/$path")
            KeyValueRow("大小", sizeLabel)
            if (BuildConfig.DEBUG) {
                KeyValueRow("Blob SHA", blobSha.take(12))
            }
        }
        Column(
            Modifier.padding(
                start = 10.dp,
                end = 10.dp,
                top = 14.dp,
                bottom = 4.dp,
            ),
        ) {
            Text(
                "Frontmatter（阅读时已隐藏）",
                style = AppTypography.labelSmall,
                color = AppColors.textMeta,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
            )
            Text(
                text = frontmatterRaw ?: "（这篇笔记没有 Frontmatter）",
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
