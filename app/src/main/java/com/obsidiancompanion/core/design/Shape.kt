package com.obsidiancompanion.core.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 形状 token —— 依据 docs/architecture/DESIGN_TOKENS.md §4。
 * 注意：主按钮 8dp（small），输入框 12dp（medium），不要「顺手统一」。
 */
object AppShapes {
    val small = RoundedCornerShape(8.dp)     // 按钮、标准卡片、callout、冲突卡、embed 卡
    val medium = RoundedCornerShape(12.dp)   // 搜索框、输入框、代码块、RepoCard
    val large = RoundedCornerShape(16.dp)    // 特色大容器（App 层基本不用）
    val sheetTop = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val pill = CircleShape                     // chip、badge、开关、进度条、toast
    val micro = RoundedCornerShape(4.dp)       // 行内代码底
    val checkbox = RoundedCornerShape(5.dp)    // 任务列表勾选框
}
