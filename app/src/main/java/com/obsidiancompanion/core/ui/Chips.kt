package com.obsidiancompanion.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppTypography

/** pill 芯片（原型 .st-chip：高 34 / 横 13；选中 = fg 反转） */
@Composable
fun AppChip(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
) {
    val container = when {
        selected -> AppColors.textPrimary
        danger -> AppColors.dangerChipBg
        else -> AppColors.surface
    }
    val content = when {
        selected -> AppColors.surface
        danger -> AppColors.danger
        else -> AppColors.textTertiary
    }
    val borderColor = when {
        selected -> AppColors.textPrimary
        danger -> AppColors.danger.copy(alpha = 0.28f)
        else -> AppColors.border
    }
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(AppShapes.pill)
            .background(container)
            .border(1.dp, borderColor, AppShapes.pill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = AppTypography.chipText, color = content)
    }
}

/** 小徽标（原型 .bdg：Private） */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AppTypography.badgeText,
        color = AppColors.textTertiary,
        modifier = modifier
            .clip(AppShapes.pill)
            .border(1.dp, AppColors.borderStrong, AppShapes.pill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Onboarding 步骤指示器（原型 .dots：6dp 圆点，当前 = fg） */
@Composable
fun DotsIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(AppShapes.pill)
                    .background(if (index == current) AppColors.textPrimary else AppColors.borderStrong),
            )
        }
    }
}
