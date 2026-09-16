package com.obsidiancompanion.core.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * 唯一主题入口：浅色单主题。
 * Material 3 仅作底层 primitive；视觉以本设计系统为准（flat / 0 elevation / ring border）。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = AppColors.accent,
        onPrimary = AppColors.onAccent,
        secondary = AppColors.textSecondary,
        onSecondary = AppColors.surface,
        background = AppColors.background,
        onBackground = AppColors.textPrimary,
        surface = AppColors.surface,
        onSurface = AppColors.textPrimary,
        surfaceVariant = AppColors.surfaceWarm,
        onSurfaceVariant = AppColors.textTertiary,
        outline = AppColors.borderStrong,
        error = AppColors.danger,
        onError = AppColors.surface,
    )
    // 仅为 M3 内部组件（AlertDialog / Snackbar / ModalBottomSheet）提供合理默认；
    // 业务组件一律直接使用 AppTypography / AppColors。
    val typography = Typography(
        titleLarge = AppTypography.appBarTitle,
        titleMedium = AppTypography.bodyBase,
        bodyLarge = AppTypography.bodyBase,
        bodyMedium = AppTypography.bodySmall,
        bodySmall = AppTypography.caption,
        labelLarge = AppTypography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        labelMedium = AppTypography.chipText,
        labelSmall = AppTypography.badgeText,
    )
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        content = content,
    )
}
