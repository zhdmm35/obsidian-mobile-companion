package com.obsidiancompanion.core.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距 scale —— 依据 docs/architecture/DESIGN_TOKENS.md §3。
 */
object AppSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
    val huge: Dp = 48.dp

    // ── 语义别名 ──────────────────────────────────────────────
    val screenPaddingHorizontal: Dp = lg       // 列表屏（home/files/search/settings）
    val readerPaddingHorizontal: Dp = xl       // Reader / Editor 正文
    val listRowVertical: Dp = md               // 列表行上下 padding（原型 rn 12 / frow 13 归一）
    val sectionSpacingTop: Dp = xl             // 原型 22px 归一到 20
    val screenBottomPadding: Dp = 22.dp        // 滚动区底部留白（避让底栏）
}
