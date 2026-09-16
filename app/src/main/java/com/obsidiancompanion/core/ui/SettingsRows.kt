package com.obsidiancompanion.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography

/**
 * 设置行（原型 .set-row：上下 14 / 左右 16）。
 * 三变体：只读（label + value）/ 尾部自定义控件 / 可点 entry（chevron）。
 */
@Composable
fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = AppTypography.bodyBase, color = AppColors.textSecondary)
        if (value != null) {
            Spacer(Modifier.weight(1f))
            Text(value, style = AppTypography.caption, color = AppColors.textTertiary)
        }
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
        if (onClick != null && value == null && trailing == null) {
            Spacer(Modifier.weight(1f))
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = AppColors.textMeta,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 46×27dp 开关（原型 .sw：on = accent，off = 暖沙底 + 描边） */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) AppColors.accent else AppColors.surfaceWarm,
        animationSpec = tween(150),
        label = "switchTrack",
    )
    val borderColor = if (checked) AppColors.accent else AppColors.borderStrong
    val knobX by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (checked) 21.dp else 3.dp,
        animationSpec = tween(180, easing = com.obsidiancompanion.core.design.AppMotion.easeStandard),
        label = "switchKnob",
    )
    Box(
        modifier = modifier
            .width(46.dp)
            .size(width = 46.dp, height = 27.dp)
            .clip(AppShapes.pill)
            .background(trackColor)
            .border(1.dp, borderColor, AppShapes.pill)
            .clickable { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobX, y = 2.5.dp)
                .size(20.dp)
                .clip(AppShapes.pill)
                .background(AppColors.surface),
        )
    }
}
