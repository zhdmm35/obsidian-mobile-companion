package com.obsidiancompanion.data.repository

import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §50：Tree 索引解析 —— 同目录优先 / 全局唯一 basename / path / 缺失 / 歧义 / .md 省略。 */
class VaultLinkResolverTest {

    private fun entry(path: String, kind: EntryKind = EntryKind.MARKDOWN): RepoEntryEntity {
        val name = path.substringAfterLast('/')
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        return RepoEntryEntity(
            repoId = "o/r",
            path = path,
            name = name,
            parentPath = parent.takeIf { it.isNotEmpty() && it != path },
            kind = kind,
            blobSha = "sha-$path",
            size = 100L,
            observedChangedAt = null,
        )
    }

    private fun entriesOf(vararg paths: String): List<RepoEntryEntity> = paths.map { p ->
        entry(p, kind = when (p.substringAfterLast('.', "").lowercase()) {
            "png", "jpg", "jpeg", "webp", "gif" -> EntryKind.IMAGE
            "md" -> EntryKind.MARKDOWN
            else -> EntryKind.OTHER
        })
    }

    private fun resolver(vararg paths: String): VaultLinkResolver = VaultLinkResolver { entriesOf(*paths) }

    private val tree = arrayOf(
        "Java/Spring Boot.md",
        "Work/Spring Boot.md",
        "AI/Claude Code.md",
        "Java/deep/nested note.md",
        "Java/architecture.png",
        "assets/logo.png",
        "assets/banner.png",
        "docs/index.md",
    )

    /* ── 笔记解析 ─────────────────────────────────────────── */

    @Test
    fun `same folder priority`() = runTest {
        // 当前笔记在 Java/ 下 → [[Spring Boot]] 命中同目录（即便全局还有一份同名）
        val r = resolver(*tree).resolveNote("o/r", "Spring Boot", null, "Java/其他.md")
        assertEquals(LinkResolution.Note("Java/Spring Boot.md", null), r)
    }

    @Test
    fun `unique basename global match`() = runTest {
        val r = resolver(*tree).resolveNote("o/r", "Claude Code", null, "别的/当前.md")
        assertEquals(LinkResolution.Note("AI/Claude Code.md", null), r)
    }

    @Test
    fun `ambiguous basename without same-dir hit`() = runTest {
        // 两个 Spring Boot.md 且当前目录都没有 → Ambiguous，不随机选（§12）
        val r = resolver(*tree).resolveNote("o/r", "Spring Boot", null, "docs/index.md")
        assertTrue(r is LinkResolution.Ambiguous)
        assertEquals(
            listOf("Java/Spring Boot.md", "Work/Spring Boot.md"),
            (r as LinkResolution.Ambiguous).candidates.sorted(),
        )
    }

    @Test
    fun `explicit path resolves root relative`() = runTest {
        val r = resolver(*tree).resolveNote("o/r", "Work/Spring Boot", null, "Java/x.md")
        assertEquals(LinkResolution.Note("Work/Spring Boot.md", null), r)
    }

    @Test
    fun `explicit path with md suffix`() = runTest {
        val r = resolver(*tree).resolveNote("o/r", "AI/Claude Code.md", null, null)
        assertEquals(LinkResolution.Note("AI/Claude Code.md", null), r)
    }

    @Test
    fun `path relative to current folder`() = runTest {
        val r = resolver(*tree).resolveNote("o/r", "deep/nested note", null, "Java/current.md")
        assertEquals(LinkResolution.Note("Java/deep/nested note.md", null), r)
    }

    @Test
    fun `md omission basename`() = runTest {
        val r = resolver(*tree).resolveNote("o/r", "index", null, "其他/now.md")
        assertEquals(LinkResolution.Note("docs/index.md", null), r)
    }

    @Test
    fun `missing target not found`() = runTest {
        val r = resolver(*tree).resolveNote("o/r", "不存在的笔记", null, null)
        assertEquals(LinkResolution.NotFound, r)
    }

    @Test
    fun `same note anchor`() = runTest {
        val r = resolver(*tree).resolveNote("o/r", "", "启动流程", "Java/x.md")
        assertEquals(LinkResolution.SameNote("启动流程"), r)
    }

    @Test
    fun `heading carried into resolution`() = runTest {
        val r = resolver(*tree).resolveNote("o/r", "Claude Code", "安装", "docs/index.md")
        assertEquals(LinkResolution.Note("AI/Claude Code.md", "安装"), r)
    }

    @Test
    fun `case insensitive fallback`() = runTest {
        val r = resolver(*tree).resolveNote("o/r", "claude code", null, null)
        assertEquals(LinkResolution.Note("AI/Claude Code.md", null), r)
    }

    @Test
    fun `leading slash and dot slash normalized`() = runTest {
        val r1 = resolver(*tree).resolveNote("o/r", "/AI/Claude Code", null, null)
        assertEquals(LinkResolution.Note("AI/Claude Code.md", null), r1)
        val r2 = resolver(*tree).resolveNote("o/r", "./AI/Claude Code", null, null)
        assertEquals(LinkResolution.Note("AI/Claude Code.md", null), r2)
    }

    @Test
    fun `path miss falls back to basename`() = runTest {
        // 路径不存在但最后一段全局唯一 → 按 basename 命中
        val r = resolver(*tree).resolveNote("o/r", "不存在的目录/Claude Code", null, null)
        assertEquals(LinkResolution.Note("AI/Claude Code.md", null), r)
    }

    /* ── 图片解析 ─────────────────────────────────────────── */

    @Test
    fun `image same folder priority`() = runTest {
        val r = resolver(*tree).resolveImage("o/r", "architecture.png", "Java/note.md")
        assertEquals("Java/architecture.png", (r as ImageResolution.Found).path)
    }

    @Test
    fun `image unique global basename`() = runTest {
        val r = resolver(*tree).resolveImage("o/r", "logo.png", null)
        assertEquals("assets/logo.png", (r as ImageResolution.Found).path)
    }

    @Test
    fun `image ambiguous`() = runTest {
        val r = resolver("a/shot.png", "b/shot.png").resolveImage("o/r", "shot.png", "c/note.md")
        assertTrue(r is ImageResolution.Ambiguous)
    }

    @Test
    fun `image missing`() = runTest {
        val r = resolver(*tree).resolveImage("o/r", "nope.png", null)
        assertEquals(ImageResolution.NotFound, r)
    }

    @Test
    fun `image path form`() = runTest {
        val r = resolver(*tree).resolveImage("o/r", "assets/logo.png", null)
        assertEquals("assets/logo.png", (r as ImageResolution.Found).path)
    }

    @Test
    fun `native relative url resolves`() = runTest {
        val r = resolver(*tree).resolveRelativeImagePath("o/r", "architecture.png", "Java/n.md")
        assertEquals("Java/architecture.png", (r as ImageResolution.Found).path)
    }

    @Test
    fun `native image with dot dot relative path`() = runTest {
        // Markdown 原生图片 `![alt](../附件/架构图.png)`（§31）
        val r = resolver(*tree).resolveRelativeImagePath("o/r", "../assets/logo.png", "docs/n.md")
        assertEquals("assets/logo.png", (r as ImageResolution.Found).path)
    }

    @Test
    fun `external http url not resolved`() = runTest {
        val r = resolver(*tree).resolveRelativeImagePath("o/r", "https://example.com/a.png", null)
        assertEquals(ImageResolution.NotFound, r)
    }

    /* ── findEntry（图片查看器按精确路径定位）───────────────── */

    @Test
    fun `findEntry exact path hit`() = runTest {
        val r = resolver(*tree).findEntry("o/r", "assets/logo.png")
        assertEquals("assets/logo.png", r?.path)
        assertEquals(EntryKind.IMAGE, r?.kind)
    }

    @Test
    fun `findEntry miss returns null`() = runTest {
        assertEquals(null, resolver(*tree).findEntry("o/r", "assets/none.png"))
    }

    @Test
    fun `findEntry is case sensitive`() = runTest {
        // Tree path 是规范形式：查看器持有精确路径，不做大小写兜底
        assertEquals(null, resolver(*tree).findEntry("o/r", "Assets/logo.png"))
    }

    /* ── 快照版 API（调用方持有一份 entries 批量解析，语义与 suspend 版一致）────────── */

    @Test
    fun `snapshot resolveNote resolves unique basename`() {
        val r = resolver(*tree).resolveNote(entriesOf(*tree), "Claude Code", null, null)
        assertEquals("AI/Claude Code.md", (r as LinkResolution.Note).path)
    }

    @Test
    fun `snapshot resolveNote prefers same directory and reports global ambiguity`() {
        val entries = entriesOf(*tree)
        val sameDir = resolver(*tree).resolveNote(entries, "Spring Boot", null, "Java/x.md")
        assertEquals("Java/Spring Boot.md", (sameDir as LinkResolution.Note).path)
        assertTrue(resolver(*tree).resolveNote(entries, "Spring Boot", null, null) is LinkResolution.Ambiguous)
    }

    @Test
    fun `snapshot resolveImage resolves same directory first`() {
        val r = resolver(*tree).resolveImage(entriesOf(*tree), "logo.png", "assets/a.md")
        assertEquals("assets/logo.png", (r as ImageResolution.Found).path)
    }

    @Test
    fun `snapshot resolveNote empty target is same-note anchor`() {
        val r = resolver(*tree).resolveNote(entriesOf(*tree), "", "标题", "docs/index.md")
        assertEquals(LinkResolution.SameNote("标题"), r)
    }
}
