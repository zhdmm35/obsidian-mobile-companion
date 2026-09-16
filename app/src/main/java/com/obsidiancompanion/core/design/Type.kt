package com.obsidiancompanion.core.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体与文字样式 —— 依据 docs/architecture/DESIGN_TOKENS.md §2。
 *
 * Display：系统 Serif（V1 决策；走查不达标再捆绑 Noto Serif SC）
 * Body：系统 Sans
 * Mono：系统 Monospace
 */
object AppFonts {
    val display = FontFamily.Serif
    val body = FontFamily.Default
    val mono = FontFamily.Monospace
}

object AppTypography {

    // ── 标题（Serif）──────────────────────────────────────────
    val displayLarge = TextStyle(fontFamily = AppFonts.display, fontSize = 25.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium)
    val screenTitle = TextStyle(fontFamily = AppFonts.display, fontSize = 24.sp, fontWeight = FontWeight.Medium)
    val readerTitle = TextStyle(fontFamily = AppFonts.display, fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.Medium)
    val titleMedium = TextStyle(fontFamily = AppFonts.display, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium)
    val appBarTitle = TextStyle(fontFamily = AppFonts.display, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val titleSmall = TextStyle(fontFamily = AppFonts.display, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    val rowTitleLarge = TextStyle(fontFamily = AppFonts.display, fontSize = 17.5.sp, fontWeight = FontWeight.Medium)
    val rowTitle = TextStyle(fontFamily = AppFonts.display, fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
    val rowTitleSmall = TextStyle(fontFamily = AppFonts.display, fontSize = 16.sp, fontWeight = FontWeight.Medium)

    // ── Markdown 标题（h1 忠实渲染，不跳过首个 H1）─────────────
    val mdH1 = TextStyle(fontFamily = AppFonts.display, fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium)
    val mdH3 = TextStyle(fontFamily = AppFonts.display, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val mdH4 = TextStyle(fontFamily = AppFonts.body, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
    val mdH5 = TextStyle(fontFamily = AppFonts.body, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textSecondary)
    val mdH6 = TextStyle(fontFamily = AppFonts.body, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textTertiary)
    val embedTitle = TextStyle(fontFamily = AppFonts.display, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)

    // ── 正文与 UI（Sans）──────────────────────────────────────
    val bodyReader = TextStyle(fontFamily = AppFonts.body, fontSize = 17.sp, lineHeight = 30.sp)   // 阅读正文灵魂参数 1.78
    val bodyBase = TextStyle(fontFamily = AppFonts.body, fontSize = 15.5.sp, lineHeight = 25.sp)
    val bodyMedium = TextStyle(fontFamily = AppFonts.body, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val sectionTitle = TextStyle(fontFamily = AppFonts.body, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
    val bodySmall = TextStyle(fontFamily = AppFonts.body, fontSize = 13.5.sp, lineHeight = 23.sp)
    val caption = TextStyle(fontFamily = AppFonts.body, fontSize = 12.5.sp, lineHeight = 21.sp)
    val tableText = TextStyle(fontFamily = AppFonts.body, fontSize = 14.sp)
    val labelSmall = TextStyle(fontFamily = AppFonts.body, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.72.sp)

    // ── 组件字号 ──────────────────────────────────────────────
    val chipText = TextStyle(fontFamily = AppFonts.body, fontSize = 12.5.sp)
    val bottomNavLabel = TextStyle(fontFamily = AppFonts.body, fontSize = 10.5.sp)
    val badgeText = TextStyle(fontFamily = AppFonts.body, fontSize = 10.5.sp)
    val fileBadge = TextStyle(fontFamily = AppFonts.mono, fontSize = 11.5.sp)                      // 冲突副本文件名 badge

    // ── Mono ─────────────────────────────────────────────────
    val codeInline = TextStyle(fontFamily = AppFonts.mono, fontSize = 13.5.sp)
    val codeBlock = TextStyle(fontFamily = AppFonts.mono, fontSize = 13.sp, lineHeight = 23.sp)
    val editorSource = TextStyle(fontFamily = AppFonts.mono, fontSize = 14.sp, lineHeight = 25.sp) // 编辑器源文本
}
