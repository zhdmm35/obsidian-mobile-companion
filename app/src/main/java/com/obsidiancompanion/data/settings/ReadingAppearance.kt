package com.obsidiancompanion.data.settings

/** 本机阅读偏好，沿用系统的字体缩放；不影响 Markdown 原文。 */
data class ReadingAppearance(val fontSize: Int = 17, val lineSpacing: Int = 100) {
    fun normalized() = copy(
        fontSize = fontSize.coerceIn(14, 26),
        lineSpacing = lineSpacing.takeIf { it in listOf(85, 100, 115) } ?: 100,
    )

    val fontScale: Float get() = normalized().fontSize / 17f
}
