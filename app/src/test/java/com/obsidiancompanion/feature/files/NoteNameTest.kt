package com.obsidiancompanion.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 新建笔记名称合法化：补后缀 / 拒绝路径逃逸与隐藏名。 */
class NoteNameTest {

    @Test
    fun `plain name gets md suffix`() {
        assertEquals("购物清单.md", sanitizeNoteName("购物清单"))
    }

    @Test
    fun `existing md suffix kept as-is`() {
        assertEquals("a.md", sanitizeNoteName("a.md"))
        assertEquals("a.MD", sanitizeNoteName("a.MD"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("a.md", sanitizeNoteName("  a  "))
    }

    @Test
    fun `empty or extension-only rejected`() {
        assertNull(sanitizeNoteName(""))
        assertNull(sanitizeNoteName("   "))
        assertNull(sanitizeNoteName(".md"))
    }

    @Test
    fun `path separators and colon rejected`() {
        assertNull(sanitizeNoteName("a/b"))
        assertNull(sanitizeNoteName("a\\b"))
        assertNull(sanitizeNoteName("../escape"))
        assertNull(sanitizeNoteName("a:b"))
    }

    @Test
    fun `hidden dot-name rejected`() {
        assertNull(sanitizeNoteName(".obsidian-note"))
    }
}
