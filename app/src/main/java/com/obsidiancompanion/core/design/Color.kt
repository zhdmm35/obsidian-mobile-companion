package com.obsidiancompanion.core.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 设计 token —— 依据 docs/architecture/DESIGN_TOKENS.md §1。
 * 浅色 / 深色双主题：全部色值集中在 [AppPalettes] 的两份 [AppPalette] 实例里；
 * 调用方仍读 `AppColors.xxx`（getter 委托当前 palette），主题切换整体换 palette 即可。
 */
class AppPalette(
    // ── 表面（3 级）───────────────────────────────────────────
    val background: Color,
    val surface: Color,
    val surfaceWarm: Color,
    // ── 文字（4 级暖灰）───────────────────────────────────────
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textMeta: Color,
    // ── 边框（2 级）───────────────────────────────────────────
    val border: Color,
    val borderStrong: Color,
    // ── 品牌与语义 ────────────────────────────────────────────
    val accent: Color,
    val onAccent: Color,
    val accentPressed: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    // ── 预计算混色（对应原型 color-mix）────────────────────────
    val calloutNoteBg: Color,
    val calloutWarnBg: Color,
    val conflictBg: Color,
    val calloutNoteTitle: Color,
    val calloutWarnTitle: Color,
    val calloutDangerBg: Color,
    val calloutDangerTitle: Color,
    val calloutSuccessBg: Color,
    val calloutSuccessTitle: Color,
    // ── 特殊组件 ──────────────────────────────────────────────
    val codeBlockBg: Color,
    val codeBlockText: Color,
    val toastBg: Color,
    val toastText: Color,
    val scrim: Color,
) {
    // ── 派生色（随基色自动适配两套主题）────────────────────────
    val calloutNoteBorder = accent.copy(alpha = 0.16f)
    val calloutWarnBorder = warning.copy(alpha = 0.26f)
    val conflictBorder = danger.copy(alpha = 0.26f)
    val dangerChipBg = danger.copy(alpha = 0.06f)
    val calloutDangerBorder = danger.copy(alpha = 0.30f)
    val calloutSuccessBorder = success.copy(alpha = 0.30f)
    val quoteBar = textPrimary.copy(alpha = 0.28f)
    val codeLangLabel = codeBlockText.copy(alpha = 0.6f)
}

/** 两套调色板。深色为「暖炭 + terracotta」的暗化变体：同色系、非纯黑，保持羊皮纸品牌温度。 */
object AppPalettes {

    val Light = AppPalette(
        background = Color(0xFFF5F4ED),     // 羊皮纸页面画布
        surface = Color(0xFFFAF9F5),        // 暖白卡片/底栏/sheet
        surfaceWarm = Color(0xFFE8E6DC),    // 暖沙：次级按钮底、离线条、行内代码底、进度轨道
        textPrimary = Color(0xFF141413),
        textSecondary = Color(0xFF3D3D3A),
        textTertiary = Color(0xFF5E5D59),
        textMeta = Color(0xFF87867F),
        border = Color(0xFFF0EEE6),
        borderStrong = Color(0xFFE8E6DC),   // 与 SurfaceWarm 同值不同语义
        accent = Color(0xFFC96442),         // terracotta 唯一品牌色
        onAccent = Color(0xFFFAF9F5),
        accentPressed = Color(0xFFAE5438),  // CSS color-mix(black 14%) 的预计算近似
        success = Color(0xFF17A34A),
        warning = Color(0xFFEAB308),
        danger = Color(0xFFB53333),         // 暖红
        calloutNoteBg = Color(0xFFF7F0EA),          // accent 6% over surface
        calloutWarnBg = Color(0xFFF9F3E0),          // warn 9% over surface
        conflictBg = Color(0xFFF7EFEB),             // danger 5% over surface
        calloutNoteTitle = Color(0xFFA55236),       // accent 混黑 18%（co-note 标题）
        calloutWarnTitle = Color(0xFF916F05),       // warn 混黑 38%（co-warn 标题）
        calloutDangerBg = Color(0xFFF8EFEF),        // danger 7% over surface
        calloutDangerTitle = Color(0xFFB53333),     // danger 已是深暖红，直接做标题色
        calloutSuccessBg = Color(0xFFEFF6F0),       // success 7% over surface
        calloutSuccessTitle = Color(0xFF0F7A38),    // success 混黑 15%
        codeBlockBg = Color(0xFF141413),    // 代码块深色反白体系
        codeBlockText = Color(0xFFFAF9F5),
        toastBg = Color(0xFF141413),
        toastText = Color(0xFFFAF9F5),
        scrim = Color(0xFF141413).copy(alpha = 0.68f),
    )

    val Dark = AppPalette(
        background = Color(0xFF1B1A17),     // 暖炭页面画布
        surface = Color(0xFF24221E),        // 深暖卡片
        surfaceWarm = Color(0xFF35322B),
        textPrimary = Color(0xFFEDEAE1),
        textSecondary = Color(0xFFC7C4BA),
        textTertiary = Color(0xFFA09D93),
        textMeta = Color(0xFF79776E),
        border = Color(0xFF302D27),
        borderStrong = Color(0xFF3E3B33),
        accent = Color(0xFFD3704C),         // terracotta 提亮一档，保暗底对比
        onAccent = Color(0xFFFAF9F5),
        accentPressed = Color(0xFFBC5E3F),
        success = Color(0xFF3FBF6B),
        warning = Color(0xFFE7BC2E),
        danger = Color(0xFFD65F5F),         // 暖红提亮，暗底可读
        calloutNoteBg = Color(0xFF2D2622),          // accent 微染于暗面
        calloutWarnBg = Color(0xFF322D1F),
        conflictBg = Color(0xFF332524),
        calloutNoteTitle = Color(0xFFE08F6F),       // accent 提亮做标题
        calloutWarnTitle = Color(0xFFD8AE3C),
        calloutDangerBg = Color(0xFF342625),
        calloutDangerTitle = Color(0xFFD65F5F),
        calloutSuccessBg = Color(0xFF233329),
        calloutSuccessTitle = Color(0xFF55C77F),
        codeBlockBg = Color(0xFF12110F),    // 比页面更暗一级的反白块
        codeBlockText = Color(0xFFEDEAE1),
        toastBg = Color(0xFFEDEAE1),        // 反色 toast（inverseSurface 语义）
        toastText = Color(0xFF1B1A17),
        scrim = Color(0xFF000000).copy(alpha = 0.68f),
    )
}

/**
 * 全局语义色入口（调用方式与浅色单主题时代完全一致：`AppColors.textPrimary`）。
 * 当前 palette 由 AppTheme 按系统深色模式设置；读取即订阅 palette state，
 * 切换时所有读取点自动重组。
 */
object AppColors {

    internal var palette by mutableStateOf(AppPalettes.Light)

    // ── 表面 ──────────────────────────────────────────────────
    val background: Color get() = palette.background
    val surface: Color get() = palette.surface
    val surfaceWarm: Color get() = palette.surfaceWarm

    // ── 文字 ──────────────────────────────────────────────────
    val textPrimary: Color get() = palette.textPrimary
    val textSecondary: Color get() = palette.textSecondary
    val textTertiary: Color get() = palette.textTertiary
    val textMeta: Color get() = palette.textMeta

    // ── 边框 ──────────────────────────────────────────────────
    val border: Color get() = palette.border
    val borderStrong: Color get() = palette.borderStrong

    // ── 品牌与语义 ────────────────────────────────────────────
    val accent: Color get() = palette.accent
    val onAccent: Color get() = palette.onAccent
    val accentPressed: Color get() = palette.accentPressed
    val success: Color get() = palette.success
    val warning: Color get() = palette.warning
    val danger: Color get() = palette.danger

    // ── 混色与 Callout ────────────────────────────────────────
    val calloutNoteBg: Color get() = palette.calloutNoteBg
    val calloutWarnBg: Color get() = palette.calloutWarnBg
    val conflictBg: Color get() = palette.conflictBg
    val calloutNoteTitle: Color get() = palette.calloutNoteTitle
    val calloutWarnTitle: Color get() = palette.calloutWarnTitle
    val calloutNoteBorder: Color get() = palette.calloutNoteBorder
    val calloutWarnBorder: Color get() = palette.calloutWarnBorder
    val conflictBorder: Color get() = palette.conflictBorder
    val dangerChipBg: Color get() = palette.dangerChipBg
    val calloutDangerBg: Color get() = palette.calloutDangerBg
    val calloutDangerTitle: Color get() = palette.calloutDangerTitle
    val calloutDangerBorder: Color get() = palette.calloutDangerBorder
    val calloutSuccessBg: Color get() = palette.calloutSuccessBg
    val calloutSuccessTitle: Color get() = palette.calloutSuccessTitle
    val calloutSuccessBorder: Color get() = palette.calloutSuccessBorder

    // ── 特殊组件 ──────────────────────────────────────────────
    val codeBlockBg: Color get() = palette.codeBlockBg
    val codeBlockText: Color get() = palette.codeBlockText
    val codeLangLabel: Color get() = palette.codeLangLabel
    val quoteBar: Color get() = palette.quoteBar
    val toastBg: Color get() = palette.toastBg
    val toastText: Color get() = palette.toastText
    val scrim: Color get() = palette.scrim
}
