package com.obsidiancompanion.feature.reader

import com.obsidiancompanion.data.markdown.MarkdownParser
import com.obsidiancompanion.model.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderOutlineTest {
    @Test
    fun `matching opening h1 uses the document title without removing content`() {
        val document = MarkdownParser.parse("---\ntags: notes\n---\n# **读书笔记**\n\n正文")
        assertTrue(hasMatchingOpeningTitle(document, "读书笔记"))
        assertTrue(document.blocks.first() is MdBlock.Heading)
        assertEquals(2, document.blocks.size)
    }

    @Test
    fun `different titles and later headings retain the file title`() {
        assertFalse(hasMatchingOpeningTitle(MarkdownParser.parse("# 正文标题"), "文件名"))
        assertFalse(hasMatchingOpeningTitle(MarkdownParser.parse("介绍\n\n# 文件名"), "文件名"))
        assertFalse(hasMatchingOpeningTitle(MarkdownParser.parse("## 文件名"), "文件名"))
        assertFalse(hasMatchingOpeningTitle(MarkdownParser.parse(""), "文件名"))
    }

    @Test
    fun `outline retains exact positions for repeated headings and ignores fenced code`() {
        val document = MarkdownParser.parse("# 标题\n\n正文\n\n## 重复\n\n```md\n# 代码中的标题\n```\n\n### **重复**")
        val outline = readerOutline(document)
        assertEquals(listOf("标题", "重复", "重复"), outline.map { it.title })
        assertEquals(listOf(0, 2, 4), outline.map { it.blockIndex })
        assertEquals(listOf(1, 2, 3), outline.map { it.level })
        assertTrue(readerOutline(MarkdownParser.parse("只有正文")).isEmpty())
    }
}
