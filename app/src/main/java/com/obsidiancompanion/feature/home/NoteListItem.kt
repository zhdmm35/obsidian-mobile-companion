package com.obsidiancompanion.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.fadeUp

/**
 * 笔记行（原型 .rn）。
 * Large（最近修改：标题+路径+时间）/ Compact（最近阅读）/ Compact+Star（收藏，星标仅在收藏区）。
 */
@Composable
fun NoteListItem(
    note: NoteRowUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    showStar: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = AppSpacing.listRowVertical)
            .fadeUp(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = note.title,
                style = if (large) AppTypography.rowTitleLarge else AppTypography.rowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (large) {
                Text(
                    text = note.folder,
                    style = AppTypography.caption,
                    color = AppColors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showStar) {
            Icon(
                AppIcons.StarFilled,
                contentDescription = "已收藏",
                tint = AppColors.textMeta,
                modifier = Modifier.size(13.dp),
            )
        }
        if (note.timeLabel != null) {
            Text(
                text = note.timeLabel,
                style = AppTypography.caption,
                color = AppColors.textTertiary,
                maxLines = 1,
            )
        }
    }
}
