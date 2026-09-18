package com.obsidiancompanion.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.BuildConfig
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.AppBottomSheet
import com.obsidiancompanion.core.ui.KeyValueRow
import com.obsidiancompanion.core.ui.SheetActionRow

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
) {
    AppBottomSheet(onDismiss = onDismiss) {
        Text(
            noteTitle,
            style = AppTypography.titleSmall,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp),
        )
        SheetActionRow(icon = AppIcons.Pencil, text = "编辑", onClick = onEdit)
        SheetActionRow(icon = AppIcons.Star, text = if (isFavorite) "取消收藏" else "收藏", onClick = onToggleFavorite)
        SheetActionRow(icon = AppIcons.Info, text = "查看文件信息", onClick = onShowFileInfo)
        SheetActionRow(icon = AppIcons.Copy, text = "复制文件路径", onClick = onCopyPath)
        SheetActionRow(icon = AppIcons.Share, text = "分享笔记", onClick = onShare)
        SheetActionRow(icon = AppIcons.Refresh, text = "刷新此笔记", onClick = onRefreshNote)
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
