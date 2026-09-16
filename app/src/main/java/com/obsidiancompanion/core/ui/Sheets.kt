package com.obsidiancompanion.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppTypography

/** ModalBottomSheet 统一封装（20dp 顶圆角 / 暖白面 / drag handle） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AppColors.surface,
        scrimColor = AppColors.scrim,
        shape = AppShapes.sheetTop,
        tonalElevation = 0.dp,
        dragHandle = {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 10.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(AppShapes.pill)
                    .background(AppColors.borderStrong),
            )
        },
    ) {
        Column(Modifier.padding(bottom = 22.dp)) {
            content()
        }
    }
}

/** Sheet 内 action 行（原型 .sh-row：图标 16 + 文字，上下 14 / 左右 10） */
@Composable
fun SheetActionRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = AppTypography.bodyBase, color = AppColors.textSecondary)
    }
}

/** 二次确认对话框（放弃编辑更改 / 重新下载 Vault 等） */
@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "取消",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.surface,
        shape = AppShapes.medium,
        title = { Text(title, style = AppTypography.bodyBase) },
        text = { Text(message, style = AppTypography.bodySmall, color = AppColors.textTertiary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = AppColors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText, color = AppColors.textTertiary)
            }
        },
    )
}
