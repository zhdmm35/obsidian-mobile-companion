package com.obsidiancompanion.core.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppMotion
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography

/** 列表分区头（原型 .sec：上 20 / 下 8，标题 15.5 W600 + 可选右侧动作） */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.screenPaddingHorizontal,
                end = AppSpacing.screenPaddingHorizontal,
                top = AppSpacing.sectionSpacingTop,
                bottom = AppSpacing.sm,
            ),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = AppTypography.sectionTitle)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** 左键右值行（原型 .kv：上下 13 / 左右 16） */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AppColors.textTertiary,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = AppTypography.bodyBase, color = AppColors.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = AppTypography.bodySmall, color = valueColor)
    }
}

/** 居中空态（原型 .empty：上 46 / 横 32，图标 + 标题 + 副标题） */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 46.dp, horizontal = AppSpacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.textPrimary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(title, style = AppTypography.bodyBase.copy(fontWeight = FontWeight.SemiBold))
        if (subtitle != null) {
            Text(
                subtitle,
                style = AppTypography.caption,
                color = AppColors.textTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 1px 分隔线（原型 --border） */
@Composable
fun AppHorizontalDivider(modifier: Modifier = Modifier, color: Color = AppColors.border) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/** 列表项入场动画（原型 fadeUp 220ms：透明度 + 下移 6dp） */
fun Modifier.fadeUp(): Modifier = composed {
    var started by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(AppMotion.FADE_UP, easing = AppMotion.easeStandard),
        label = "fadeUpAlpha",
    )
    val ty by animateDpAsState(
        targetValue = if (started) 0.dp else 6.dp,
        animationSpec = tween(AppMotion.FADE_UP, easing = AppMotion.easeStandard),
        label = "fadeUpTy",
    )
    LaunchedEffect(Unit) { started = true }
    graphicsLayer {
        this.alpha = alpha
        translationY = ty.toPx()
    }
}
