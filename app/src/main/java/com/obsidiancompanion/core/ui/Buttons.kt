package com.obsidiancompanion.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppTypography

/**
 * 三态按钮 —— 原型 .btn / .btn-pri / .btn-sec / .btn-ghost / .btn-sm / .btn-block。
 * 高度 46dp（small 38dp），圆角 8dp（注意不是 12dp）。
 */

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    small: Boolean = false,
    block: Boolean = false,
) {
    BaseButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        small = small,
        block = block,
        container = AppColors.accent,
        content = AppColors.onAccent,
        ring = AppColors.accent,
    )
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    block: Boolean = false,
) {
    BaseButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        small = small,
        block = block,
        container = AppColors.surfaceWarm,
        content = AppColors.textSecondary,
        ring = AppColors.border,
    )
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    BaseButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        small = small,
        block = false,
        container = Color.Transparent,
        content = AppColors.textSecondary,
        ring = null,
        horizontalPadding = 12.dp,
    )
}

@Composable
private fun BaseButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    small: Boolean,
    block: Boolean,
    container: Color,
    content: Color,
    ring: Color?,
    horizontalPadding: Dp = 18.dp,
) {
    val shape = AppShapes.small
    Row(
        modifier = modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .height(if (small) 38.dp else 46.dp)
            .clip(shape)
            .background(container)
            .then(if (ring != null) Modifier.border(1.dp, ring, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = if (small) AppTypography.bodyMedium.copy(fontSize = 14.sp) else AppTypography.bodyMedium,
            color = if (enabled) content else content.copy(alpha = 0.5f),
        )
    }
}

/** 44dp 图标按钮，ripple 圆角 8dp */
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = AppColors.textPrimary,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(AppShapes.small)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}
