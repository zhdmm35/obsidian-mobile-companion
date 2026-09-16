package com.obsidiancompanion.core.design

import androidx.compose.animation.core.CubicBezierEasing

/**
 * 动效 token —— 依据 docs/architecture/DESIGN_TOKENS.md §6。
 * 安静、短促、有目的。
 */
object AppMotion {
    const val FAST = 150
    const val BASE = 200
    const val FADE_UP = 220
    const val TOAST = 250
    val easeStandard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
