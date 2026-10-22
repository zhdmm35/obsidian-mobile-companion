package com.obsidiancompanion.feature.reader

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.obsidiancompanion.data.settings.ReadingAppearance
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingAppearanceTest {
    @Test fun corruptPreferencesAreClamped() {
        assertEquals(ReadingAppearance(14, 100), ReadingAppearance(-1, 0).normalized())
        assertEquals(ReadingAppearance(26, 115), ReadingAppearance(100, 115).normalized())
    }

    @Test fun defaultsPreserveExistingTypography() {
        val style = TextStyle(fontSize = 17.sp, lineHeight = 30.sp)
        assertEquals(style, style.withReadingAppearance(ReadingAppearance()))
    }

    @Test fun textAndLineHeightScaleTogether() {
        val style = TextStyle(fontSize = 17.sp, lineHeight = 30.sp)
            .withReadingAppearance(ReadingAppearance(26, 115))
        assertEquals(26f, style.fontSize.value, 0.001f)
        assertEquals(30f * 26 / 17 * 1.15f, style.lineHeight.value, 0.001f)
    }

    @Test fun unspecifiedUnitsRemainUnspecified() {
        val style = TextStyle().withReadingAppearance(ReadingAppearance(26, 85))
        assertEquals(TextUnit.Unspecified, style.fontSize)
        assertEquals(TextUnit.Unspecified, style.lineHeight)
    }
}
