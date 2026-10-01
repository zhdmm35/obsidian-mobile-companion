package com.obsidiancompanion.feature.search

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHighlightTest {
    private val style = SpanStyle(fontWeight = FontWeight.SemiBold)

    @Test fun cjkAndEmojiKeepOriginalTextAndUtf16Offsets() {
        val text = "📚同步笔记，再同步"
        val result = highlightSearchMatches(text, "同步", style)
        assertEquals(text, result.text)
        assertEquals(listOf(2 to 4, 8 to 10), result.spanStyles.map { it.start to it.end })
    }

    @Test fun ignoresCaseAndTrimsQueryWithoutChangingOriginalText() {
        val result = highlightSearchMatches("TODO todo ToDo", " todo ", style)
        assertEquals("TODO todo ToDo", result.text)
        assertEquals(listOf(0 to 4, 5 to 9, 10 to 14), result.spanStyles.map { it.start to it.end })
    }

    @Test fun matchesLiteralRegexCharacters() {
        val result = highlightSearchMatches("[a.*] a.* aaa", "a.*", style)
        assertEquals(listOf(1 to 4, 6 to 9), result.spanStyles.map { it.start to it.end })
    }

    @Test fun blankAndAbsentQueriesAddNoStyles() {
        assertTrue(highlightSearchMatches("内容", " \n ", style).spanStyles.isEmpty())
        assertTrue(highlightSearchMatches("内容", "没有", style).spanStyles.isEmpty())
    }

    @Test fun repeatedMatchesDoNotOverlap() {
        val result = highlightSearchMatches("aaaa", "aa", style)
        assertEquals(listOf(0 to 2, 2 to 4), result.spanStyles.map { it.start to it.end })
    }

    @Test fun contentHighlightUsesSameWhitespaceNormalizationAsSearch() {
        val query = " git\n  rebase "
        val match = highlightSearchMatches("先执行 git rebase 再推送", normalizeContentQuery(query), style)
        assertEquals(listOf(4 to 14), match.spanStyles.map { it.start to it.end })
        // 文件名是字面匹配，不能把用户输入中的连续空白折叠掉。
        assertTrue(highlightSearchMatches("git rebase.md", query, style).spanStyles.isEmpty())
    }
}
