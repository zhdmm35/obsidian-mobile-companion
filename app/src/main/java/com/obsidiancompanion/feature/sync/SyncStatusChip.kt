package com.obsidiancompanion.feature.sync

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.model.SyncStatus

/** 在线模式语义（§55）：已更新 / 刷新中 / 离线 / 刷新失败 / 有冲突（演示态） */
private fun SyncStatus.meta(): Triple<ImageVector, String, Boolean> = when (this) {
    SyncStatus.UPDATED -> Triple(AppIcons.CheckCircle, "已更新", false)
    SyncStatus.REFRESHING -> Triple(AppIcons.Refresh, "刷新中…", false)
    SyncStatus.OFFLINE -> Triple(AppIcons.CloudOff, "离线", false)
    SyncStatus.REFRESH_FAILED -> Triple(AppIcons.Alert, "刷新失败", true)
    SyncStatus.CONFLICT -> Triple(AppIcons.Alert, "有冲突", true)
}

/** 首页右上状态 chip（原型 chipHtml()）：5 态，点击进入数据刷新页 */
@Composable
fun SyncStatusChip(
    status: SyncStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, label, isBad) = status.meta()
    val contentColor = if (isBad) AppColors.danger else AppColors.textTertiary
    val iconTint = when {
        status == SyncStatus.REFRESHING -> AppColors.accent
        isBad -> AppColors.danger
        else -> AppColors.textMeta
    }
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(AppShapes.pill)
            .background(if (isBad) AppColors.dangerChipBg else AppColors.surface)
            .border(1.dp, if (isBad) AppColors.danger.copy(alpha = 0.28f) else AppColors.border, AppShapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        if (status == SyncStatus.REFRESHING) {
            SpinningIcon(icon, tint = iconTint, size = 13.dp, contentDescription = label)
        } else {
            Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(13.dp))
        }
        Text(label, style = AppTypography.chipText, color = contentColor)
    }
}

/** 1s 线性旋转图标（原型 .spin） */
@Composable
fun SpinningIcon(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.Dp,
    contentDescription: String?,
) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "spinAngle",
    )
    Icon(
        icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = angle },
    )
}
