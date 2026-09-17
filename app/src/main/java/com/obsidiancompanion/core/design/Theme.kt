package com.obsidiancompanion.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * 唯一主题入口：跟随系统深色 / 浅色。
 * Material 3 仅作底层 primitive；视觉以本设计系统为准（flat / 0 elevation / ring border）。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) AppPalettes.Dark else AppPalettes.Light
    // 组合期间同步指定 palette（幂等，同实例不触发额外重组）：
    // 保证下方 content 首次组合即读到正确主题，无一帧浅色闪烁
    AppColors.palette = palette

    val m3Colors = if (palette === AppPalettes.Dark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.textSecondary,
            onSecondary = palette.surface,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceWarm,
            onSurfaceVariant = palette.textTertiary,
            outline = palette.borderStrong,
            error = palette.danger,
            onError = palette.surface,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.textSecondary,
            onSecondary = palette.surface,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceWarm,
            onSurfaceVariant = palette.textTertiary,
            outline = palette.borderStrong,
            error = palette.danger,
            onError = palette.surface,
        )
    }
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
        colorScheme = m3Colors,
        typography = typography,
        content = content,
    )
}
