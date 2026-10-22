package com.obsidiancompanion.data.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureAppendTest {
    @Test fun retryIsIdempotentButSeparateIdenticalSharesArePreserved() {
        val once = CaptureAppend.append("# Inbox\r\n原文  \r\n", "first", "中文 🎉")
        assertTrue(once.startsWith("# Inbox\r\n原文  \r\n"))
        assertEquals(once, CaptureAppend.append(once, "first", "中文 🎉"))
        val twice = CaptureAppend.append(once, "second", "中文 🎉")
        assertEquals(2, Regex("中文 🎉").findAll(twice).count())
    }

    @Test fun markerCannotInjectMarkupAndNeedsAnExactLine() {
        val marker = CaptureAppend.marker("id -->\n# injected")
        assertTrue(CaptureAppend.isMarker(marker))
        assertFalse(CaptureAppend.alreadyAppended("prefix $marker", "id -->\n# injected"))
        assertTrue(CaptureAppend.alreadyAppended("$marker\r\n", "id -->\n# injected"))
    }
}
