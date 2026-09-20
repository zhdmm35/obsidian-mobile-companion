package com.obsidiancompanion.feature.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.fadeUp
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.util.Format

/** 文件夹行（原型 .frow.fold：图标 + 名称 + N 项 + chevron） */
@Composable
fun FolderRow(entry: RepoEntryEntity, childCount: Int, status: String, onClick: () -> Unit, onDownload: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = 13.dp)
            .fadeUp(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AppIcons.Folder, contentDescription = null, tint = AppColors.textSecondary, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                style = AppTypography.rowTitleSmall,
                color = AppColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(status, style = AppTypography.caption, color = AppColors.textMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$childCount 项", style = AppTypography.caption, color = AppColors.textMeta)
        androidx.compose.foundation.layout.Box {
            Icon(
                AppIcons.More,
                contentDescription = "${entry.name}离线选项",
                tint = AppColors.textMeta,
                modifier = Modifier.size(24.dp).clickable { menuOpen = true },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("下载供离线阅读 / 重试") }, onClick = { menuOpen = false; onDownload() })
                DropdownMenuItem(text = { Text("清理此文件夹缓存") }, onClick = { menuOpen = false; onClear() })
            }
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = AppColors.textMeta, modifier = Modifier.size(16.dp))
    }
}

/** Markdown 文件行（原型 .frow：filetxt 图标 + 标题（不含 .md）） */
@Composable
fun FileRow(entry: RepoEntryEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = 13.dp)
            .fadeUp(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AppIcons.FileText, contentDescription = null, tint = AppColors.textSecondary, modifier = Modifier.size(20.dp))
        Text(
            entry.name.removeSuffix(".md"),
            style = AppTypography.rowTitleSmall,
            color = AppColors.textSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 附件行（原型 .frow.att：类型图标 + 名称 + 大小，弱化色；PDF/Canvas 本阶段仅显示存在 §34） */
@Composable
fun AttachmentRow(entry: RepoEntryEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val icon = when (entry.kind) {
        EntryKind.IMAGE -> AppIcons.Image
        EntryKind.PDF -> AppIcons.Pdf
        EntryKind.CANVAS -> AppIcons.Canvas
        else -> AppIcons.FileText
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = 13.dp)
            .fadeUp(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = entry.kind.name, tint = AppColors.textMeta, modifier = Modifier.size(20.dp))
        Text(
            entry.name,
            style = AppTypography.rowTitleSmall.copy(fontWeight = FontWeight.Normal),
            color = AppColors.textSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.size != null) {
            Text(Format.bytes(entry.size), style = AppTypography.caption, color = AppColors.textMeta)
        }
    }
}
