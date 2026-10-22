package com.obsidiancompanion.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import com.obsidiancompanion.data.settings.ReadingAppearance

val LocalReadingAppearance = staticCompositionLocalOf { ReadingAppearance() }

internal fun TextStyle.withReadingAppearance(value: ReadingAppearance): TextStyle {
    val appearance = value.normalized()
    val scale = appearance.fontScale
    return copy(
        fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * scale * (appearance.lineSpacing / 100f) else lineHeight,
    )
}

@Composable
internal fun readerTextStyle(style: TextStyle): TextStyle = style.withReadingAppearance(LocalReadingAppearance.current)
