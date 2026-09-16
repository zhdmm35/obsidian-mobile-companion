package com.obsidiancompanion.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 5 §31-§35：工具条文本变换与光标语义。 */
class EditorTextOpsTest {

    /* ── §32 Bold ─────────────────────────────────────────────── */

    @Test
    fun bold_withSelection_wrapsAndCursorAfterWrapped() {
        val r = EditorTextOps.wrap("abc def", 0, 3, "**", "**")
        assertEquals("**abc** def", r.text)
        assertEquals(7, r.cursor)
    }

    @Test
    fun bold_noSelection_insertsPairCursorInMiddle() {
        val r = EditorTextOps.wrap("hello", 5, 5, "**", "**")
        assertEquals("hello****", r.text)
        assertEquals(7, r.cursor) // **|**
    }

    @Test
    fun bold_emptyText_cursorInMiddle() {
        val r = EditorTextOps.wrap("", 0, 0, "**", "**")
        assertEquals("****", r.text)
        assertEquals(2, r.cursor)
    }

    /* ── §35 WikiLink ─────────────────────────────────────────── */

    @Test
    fun wikilink_withSelection() {
        val r = EditorTextOps.wrap("请看 Spring Boot 文档", 3, 14, "[[", "]]")
        assertEquals("请看 [[Spring Boot]] 文档", r.text)
        assertEquals(3 + "[[Spring Boot]]".length, r.cursor)
    }

    @Test
    fun wikilink_noSelection_cursorInMiddle() {
        val r = EditorTextOps.wrap("ab", 1, 1, "[[", "]]")
        assertEquals("a[[]]b", r.text)
        assertEquals(3, r.cursor) // a[[|]]b
    }

    /* ── §31/§33/§34 行首前缀 ─────────────────────────────────── */

    @Test
    fun heading_atLineStart() {
        val r = EditorTextOps.linePrefix("title\nbody", 0, "## ")
        assertEquals("## title\nbody", r.text)
        assertEquals(3, r.cursor)
    }

    @Test
    fun heading_midLine_prefixesWholeLine() {
        val r = EditorTextOps.linePrefix("title\nbody", 8, "## ")
        assertEquals("title\n## body", r.text)
        assertEquals(11, r.cursor)
    }

    @Test
    fun list_onEmptyLastLine() {
        val r = EditorTextOps.linePrefix("abc\n", 4, "- ")
        assertEquals("abc\n- ", r.text)
        assertEquals(6, r.cursor)
    }

    @Test
    fun task_prefixesCurrentLine() {
        val r = EditorTextOps.linePrefix("- item", 6, "- [ ] ")
        assertEquals("- [ ] - item", r.text)
        assertEquals(12, r.cursor)
    }

    @Test
    fun linePrefix_cursorPastEnd_clamped() {
        val r = EditorTextOps.linePrefix("abc", 99, "- ")
        assertEquals("- abc", r.text)
        assertEquals(5, r.cursor)
    }

    @Test
    fun heading_existingPrefix_removed() {
        val r = EditorTextOps.linePrefix("## title\nbody", 0, "## ")
        assertEquals("title\nbody", r.text)
        assertEquals(0, r.cursor)
    }

    @Test
    fun task_existingPrefix_removed() {
        val r = EditorTextOps.linePrefix("- [ ] item", 0, "- [ ] ")
        assertEquals("item", r.text)
        assertEquals(0, r.cursor)
    }

    @Test
    fun linePrefix_remove_midLineCursorShiftsLeft() {
        val r = EditorTextOps.linePrefix("## abc def", 7, "## ")
        assertEquals("abc def", r.text)
        assertEquals(4, r.cursor)
    }

    @Test
    fun linePrefix_remove_cursorClampedAtLineStart() {
        val r = EditorTextOps.linePrefix("## ab", 2, "## ")
        assertEquals("ab", r.text)
        assertEquals(0, r.cursor)
    }

    @Test
    fun linePrefix_toggle_onlyAffectsCurrentLine() {
        val r = EditorTextOps.linePrefix("a\n## b\nc", 6, "## ")
        assertEquals("a\nb\nc", r.text)
        assertEquals(3, r.cursor)
    }

    @Test
    fun wrap_selectionReversed_handledByCaller_normalizedHere() {
        // 调用方传 min..max；防御：start>end 时按空选区处理不崩溃
        val r = EditorTextOps.wrap("abc", 2, 2, "**", "**")
        assertEquals("ab****c", r.text)
        assertEquals(4, r.cursor)
    }
}
