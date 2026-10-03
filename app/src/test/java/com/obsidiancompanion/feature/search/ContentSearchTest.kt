package com.obsidiancompanion.feature.search

import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin

/** 搜索正文匹配：子串命中 / 计数 / 排序 / 摘要窗口（计数与摘要同在折叠文本上）。 */
class ContentSearchTest {

    @Test fun unicodeCaseMatching_keepsPlatformIgnoreCaseSemantics() {
        val body = (0..65535).filter { !it.toChar().isSurrogate() }.joinToString("|") { it.toChar().toString() }
        val flat = body.replace(Regex("\\s+"), " ")
        for (query in listOf("i", "k", "s", "σ", "ǅ", "ß")) {
            var count = 0
            var index = flat.indexOf(query, ignoreCase = true)
            while (index >= 0) {
                count++
                index = flat.indexOf(query, index + query.length, ignoreCase = true)
            }
            assertEquals(query, count, searchContents(snapshot("unicode.md" to body), query).single().count)
        }
    }

    @Test fun supplementaryLettersAndEmoji_keepOriginalOffsetsAndCaseRules() {
        val body = "before 𐐀 𐐨 😀 after"
        for (query in listOf("𐐀", "𐐨", "😀")) {
            val expected = mutableListOf<Int>()
            var index = body.indexOf(query, ignoreCase = true)
            while (index >= 0) {
                expected += index
                index = body.indexOf(query, index + query.length, ignoreCase = true)
            }
            val actual = searchContents(snapshot("emoji.md" to body), query).single()
            assertEquals(expected.size, actual.count)
            assertEquals(snippetWindow(body, expected.first(), query.length), actual.snippet)
        }
    }

    @Test fun streamingSearchPreservesResultsAndSkipsUncachedAndNonMarkdown() = runTest {
        val docs = snapshot("b.md" to "hit Hit", "a.md" to "hit", "c.md" to "no match")
        val uncached = entry("uncached.md")
        val directory = entry("folder").copy(kind = EntryKind.DIRECTORY)
        val loaded = mutableListOf<String>()
        val actual = searchCachedContents(docs.map { it.first } + uncached + directory, " HiT ") { e ->
            loaded += e.path
            docs.find { it.first == e }?.second
        }
        assertEquals(searchContents(docs, "HiT"), actual)
        assertEquals(listOf("b.md", "a.md", "c.md", "uncached.md"), loaded)
    }

    @Test fun blankStreamingQueryDoesNoDiskReads() = runTest {
        val matches = searchCachedContents(listOf(entry("a.md")), "   ") { error("Must not load") }
        assertTrue(matches.isEmpty())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cancellingOldQueryStopsLoadingAndNeverPublishesPartialResults() = runTest {
        val loaded = mutableListOf<String>()
        var published = false
        val job = launch {
            searchCachedContents(listOf(entry("a.md"), entry("b.md")), "hit") {
                loaded += it.path
                awaitCancellation()
            }
            published = true
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf("a.md"), loaded)
        assertTrue(!published)
    }

    @Test fun whitespaceCollapseMatchesRegexForEveryBmpCharacter() {
        val input = (0..0xffff).filter { it !in 0xd800..0xdfff }
            .joinToString("\t \n") { it.toChar().toString() }
        assertEquals(input.replace(Regex("\\s+"), " "), collapseContentWhitespace(input))
        assertEquals(" foo bar ", collapseContentWhitespace("\r\n foo\t\u000bbar\u000c "))
        assertEquals("", collapseContentWhitespace(""))
    }

    /* ── fixtures ─────────────────────────────────────────── */

    private fun entry(path: String) = RepoEntryEntity(
        repoId = "repo",
        path = path,
        name = path.substringAfterLast('/'),
        parentPath = path.substringBeforeLast('/', "").ifEmpty { null },
        kind = EntryKind.MARKDOWN,
        blobSha = "sha-$path",
        size = null,
        observedChangedAt = null,
    )

    private fun snapshot(vararg pairs: Pair<String, String>) =
        pairs.map { (path, content) -> entry(path) to content }

    /* ── searchContents ───────────────────────────────────── */

    @Test
    fun `cjk substring hit`() {
        val matches = searchContents(snapshot("a.md" to "今天讨论了知识库的同步方案"), "同步")
        assertEquals(1, matches.size)
        assertEquals("a.md", matches[0].entry.path)
        assertEquals(1, matches[0].count)
    }

    @Test
    fun `ascii match is case insensitive and keeps original casing in snippet`() {
        val matches = searchContents(snapshot("b.md" to "TODO list review"), "todo")
        assertEquals(1, matches.size)
        assertTrue(matches[0].snippet.contains("TODO"))
    }

    @Test
    fun `phrase matches across line breaks after whitespace collapse`() {
        val matches = searchContents(snapshot("f.md" to "先执行 git\nrebase 再推送"), "git rebase")
        assertEquals(1, matches.size)
        assertEquals(1, matches[0].count)
        assertTrue(matches[0].snippet.contains("git rebase"))
    }

    @Test
    fun `query whitespace runs collapse symmetrically with content`() {
        val matches = searchContents(snapshot("g.md" to "foo bar"), "foo  bar")
        assertEquals(1, matches.size)
    }

    @Test
    fun `multiple occurrences counted without overlap`() {
        val matches = searchContents(snapshot("c.md" to "关键词、关键词、关键词"), "关键词")
        assertEquals(3, matches[0].count)
        val overlapping = searchContents(snapshot("d.md" to "aaaa"), "aa")
        assertEquals(2, overlapping[0].count)
    }

    @Test
    fun `no match and blank query return empty`() {
        assertTrue(searchContents(snapshot("e.md" to "普通内容"), "不存在词").isEmpty())
        assertTrue(searchContents(snapshot("e.md" to "普通内容"), "").isEmpty())
        assertTrue(searchContents(snapshot("e.md" to "普通内容"), "   ").isEmpty())
        assertTrue(searchContents(emptyList(), "任意").isEmpty())
    }

    @Test
    fun `sorted by count desc then path asc`() {
        val matches = searchContents(
            snapshot(
                "b/两处.md" to "目标词 目标词",
                "a/也是两处.md" to "目标词 目标词",
                "c/五处.md" to "目标词目标词目标词目标词目标词",
                "a/零处.md" to "无关内容",
            ),
            "目标词",
        )
        assertEquals(listOf("c/五处.md", "a/也是两处.md", "b/两处.md"), matches.map { it.entry.path })
    }

    @Test
    fun `snippet from searchContents collapses content whitespace`() {
        // 内容比默认窗口（30/60）短 → 全覆盖、无省略号；换行折叠为单空格
        val matches = searchContents(snapshot("a.md" to "第一行\n第二行 关键词开始\n尾巴"), "关键词")
        assertEquals("第一行 第二行 关键词开始 尾巴", matches[0].snippet)
    }

    /* ── snippetWindow ────────────────────────────────────── */

    @Test
    fun `window at start and end has no ellipsis on that edge`() {
        assertEquals("关键词在开…", snippetWindow("关键词在开头", 0, 3, before = 2, after = 2))
        assertEquals("开头关键词", snippetWindow("开头关键词", 2, 3, before = 2, after = 4))
    }

    @Test
    fun `window trims leading and trailing spaces inside the cut`() {
        assertEquals("a b 关键词", snippetWindow("a b 关键词", 4, 3, before = 10, after = 10))
        assertEquals("关键词 尾…", snippetWindow("关键词 尾巴", 0, 3, before = 2, after = 2))
    }
}
