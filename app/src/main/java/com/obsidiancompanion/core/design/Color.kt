package com.obsidiancompanion.core.design

import androidx.compose.ui.graphics.Color

/**
 * 设计 token —— 依据 docs/architecture/DESIGN_TOKENS.md §1。
 * 浅色单主题（Dark Mode 在 Scope Freeze 中，V1 不做）。
 */
object AppColors {

    // ── 表面（3 级）───────────────────────────────────────────
    val background = Color(0xFFF5F4ED)     // 羊皮纸页面画布
    val surface = Color(0xFFFAF9F5)        // 暖白卡片/底栏/sheet
    val surfaceWarm = Color(0xFFE8E6DC)    // 暖沙：次级按钮底、离线条、行内代码底、进度轨道

    // ── 文字（4 级暖灰）───────────────────────────────────────
    val textPrimary = Color(0xFF141413)
    val textSecondary = Color(0xFF3D3D3A)
    val textTertiary = Color(0xFF5E5D59)
    val textMeta = Color(0xFF87867F)

    // ── 边框（2 级；BorderStrong 与 SurfaceWarm 同值不同语义）──
    val border = Color(0xFFF0EEE6)
    val borderStrong = Color(0xFFE8E6DC)

    // ── 品牌与语义 ────────────────────────────────────────────
    val accent = Color(0xFFC96442)         // terracotta 唯一品牌色
    val onAccent = Color(0xFFFAF9F5)
    val accentPressed = Color(0xFFAE5438)  // CSS color-mix(black 14%) 的预计算近似
    val success = Color(0xFF17A34A)
    val warning = Color(0xFFEAB308)
    val danger = Color(0xFFB53333)         // 暖红

    // ── 预计算混色（对应原型 color-mix，Compose 中为不透明近似）──
    val calloutNoteBg = Color(0xFFF7F0EA)          // accent 6% over surface
    val calloutWarnBg = Color(0xFFF9F3E0)          // warn 9% over surface
    val conflictBg = Color(0xFFF7EFEB)             // danger 5% over surface
    val calloutNoteTitle = Color(0xFFA55236)       // accent 混黑 18%（co-note 标题）
    val calloutWarnTitle = Color(0xFF916F05)       // warn 混黑 38%（co-warn 标题）
    val calloutNoteBorder = accent.copy(alpha = 0.16f)
    val calloutWarnBorder = warning.copy(alpha = 0.26f)
    val conflictBorder = danger.copy(alpha = 0.26f)
    val dangerChipBg = danger.copy(alpha = 0.06f)

    // ── Callout 扩展档位（Phase 4 §21：danger-like / success-like）──
    val calloutDangerBg = Color(0xFFF8EFEF)        // danger 7% over surface
    val calloutDangerTitle = danger                // danger 已是深暖红，直接做标题色
    val calloutDangerBorder = danger.copy(alpha = 0.30f)
    val calloutSuccessBg = Color(0xFFEFF6F0)       // success 7% over surface
    val calloutSuccessTitle = Color(0xFF0F7A38)    // success 混黑 15%
    val calloutSuccessBorder = success.copy(alpha = 0.30f)

    // ── 特殊组件 ──────────────────────────────────────────────
    val codeBlockBg = textPrimary           // 代码块深色反白体系
    val codeBlockText = surface
    val codeLangLabel = surface.copy(alpha = 0.6f)
    val quoteBar = textPrimary.copy(alpha = 0.28f)
    val toastBg = textPrimary
    val toastText = surface
    val scrim = textPrimary.copy(alpha = 0.68f)
}
