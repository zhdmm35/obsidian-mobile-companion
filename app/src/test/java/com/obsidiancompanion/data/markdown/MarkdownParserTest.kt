package com.obsidiancompanion.data.markdown

import com.obsidiancompanion.model.markdown.MdBlock
import com.obsidiancompanion.model.markdown.MdInline
import com.obsidiancompanion.model.markdown.displayText
import com.obsidiancompanion.model.markdown.findHeadingIndex
import com.obsidiancompanion.model.markdown.plainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §49-§53：基础 Markdown + Phase 4 Obsidian 全矩阵。
 * WikiLink / Embed / Callout / Frontmatter / 嵌套列表引用。
 */
class MarkdownParserTest {

    private fun parseBlocks(src: String): List<MdBlock> = MarkdownParser.parse(src).blocks

    private fun firstParagraphText(src: String): String =
        (parseBlocks(src).first() as MdBlock.Paragraph).inlines.plainText()

    private fun wikiInlines(src: String): List<MdInline.WikiLink> {
        val p = parseBlocks(src).first() as MdBlock.Paragraph
        return p.inlines.filterIsInstance<MdInline.WikiLink>()
    }

    /* ── 块级（Phase 3 回归）────────────────────────────── */

    @Test
    fun `headings h1 to h6`() {
        val blocks = parseBlocks("# 一\n## 二\n### 三\n#### 四\n##### 五\n###### 六")
        assertEquals(6, blocks.size)
        (1..6).forEach { level ->
            val h = blocks[level - 1] as MdBlock.Heading
            assertEquals(level, h.level)
        }
    }

    @Test
    fun `paragraph with inline styles`() {
        val p = parseBlocks("正文 **粗** *斜* ~~删~~ `码` [链](https://a.b)").first() as MdBlock.Paragraph
        val types = p.inlines.map { it::class.simpleName }
        assertTrue(types.contains("Bold"))
        assertTrue(types.contains("Italic"))
        assertTrue(types.contains("Strike"))
        assertTrue(types.contains("Code"))
        assertTrue(types.contains("Link"))
        val link = p.inlines.first { it is MdInline.Link } as MdInline.Link
        assertEquals("https://a.b", link.url)
        assertEquals("链", link.children.plainText())
    }

    @Test
    fun `bullet and ordered list with start number`() {
        val blocks = parseBlocks("- a\n- b\n\n3. 三\n4. 四")
        val bullet = blocks[0] as MdBlock.BulletList
        assertEquals(2, bullet.items.size)
        assertEquals("a", bullet.items[0].inlines.plainText())
        val ordered = blocks[1] as MdBlock.OrderedList
        assertEquals(3, ordered.startNumber)
        assertEquals(2, ordered.items.size)
    }

    @Test
    fun `task list checked and unchecked`() {
        val t = parseBlocks("- [x] 已完成\n- [ ] 未完成").first() as MdBlock.TaskList
        assertEquals(2, t.items.size)
        assertTrue(t.items[0].done)
        assertTrue(!t.items[1].done)
        assertEquals("已完成", t.items[0].inlines.plainText())
    }

    @Test
    fun `quote single and multi paragraph`() {
        val q = parseBlocks("> 第一段\n>\n> 第二段").first() as MdBlock.Quote
        assertEquals(2, q.children.size)
        assertEquals("第一段", (q.children[0] as MdBlock.Paragraph).inlines.plainText())
    }

    @Test
    fun `fenced code block with and without language`() {
        val blocks = parseBlocks("```kotlin\nval a = 1\n```\n\n```\nplain\n```")
        val withLang = blocks[0] as MdBlock.CodeBlock
        assertEquals("kotlin", withLang.language)
        assertEquals("val a = 1", withLang.code)
        val without = blocks[1] as MdBlock.CodeBlock
        assertNull(without.language)
        assertEquals("plain", without.code)
    }

    @Test
    fun `indented code block`() {
        val c = parseBlocks("    indented code").first() as MdBlock.CodeBlock
        assertEquals("indented code", c.code)
    }

    @Test
    fun `table headers and rows`() {
        val table = parseBlocks("| 环境 | 接口 |\n| --- | --- |\n| 测试 | /a |\n| 生产 | /b |").first() as MdBlock.Table
        assertEquals(listOf("环境", "接口"), table.headers.map { it.plainText() })
        assertEquals(2, table.rows.size)
        assertEquals("/b", table.rows[1][1].plainText())
    }

    @Test
    fun `thematic break variants`() {
        assertTrue(parseBlocks("---")[0] is MdBlock.HorizontalRule)
        assertTrue(parseBlocks("***")[0] is MdBlock.HorizontalRule)
    }

    @Test
    fun `standalone image becomes image block`() {
        val img = parseBlocks("![架构图](attachments/architecture.png)").first() as MdBlock.Image
        assertEquals("attachments/architecture.png", img.url)
        assertEquals("架构图", img.alt)
    }

    @Test
    fun `inline image inside paragraph kept as inline`() {
        val p = parseBlocks("前 ![小图](a.png) 后").first() as MdBlock.Paragraph
        assertTrue(p.inlines.any { it is MdInline.Image })
    }

    /* ── WikiLink（§49）──────────────────────────────────── */

    @Test
    fun `plain wikilink parses to node`() {
        val links = wikiInlines("参考 [[Spring Boot]] 做对比")
        assertEquals(1, links.size)
        assertEquals("Spring Boot", links[0].target)
        assertNull(links[0].alias)
        assertNull(links[0].heading)
        assertTrue(!links[0].embed)
        assertEquals("Spring Boot", links[0].displayText())
        assertEquals("[[Spring Boot]]", links[0].raw)
        // 前后文本保留
        assertEquals("参考 Spring Boot 做对比", firstParagraphText("参考 [[Spring Boot]] 做对比"))
    }

    @Test
    fun `wikilink with alias`() {
        val links = wikiInlines("[[Spring Boot|Spring]]")
        assertEquals("Spring Boot", links[0].target)
        assertEquals("Spring", links[0].alias)
        assertEquals("Spring", links[0].displayText()) // §14：显示 alias，点击解析 target
    }

    @Test
    fun `wikilink with heading`() {
        val links = wikiInlines("[[Spring Boot#启动流程]]")
        assertEquals("Spring Boot", links[0].target)
        assertEquals("启动流程", links[0].heading)
        assertEquals("Spring Boot › 启动流程", links[0].displayText())
    }

    @Test
    fun `wikilink with heading and alias`() {
        val links = wikiInlines("[[Spring Boot#启动流程|SB]]")
        assertEquals("Spring Boot", links[0].target)
        assertEquals("启动流程", links[0].heading)
        assertEquals("SB", links[0].alias)
    }

    @Test
    fun `same-note heading link`() {
        val links = wikiInlines("[[#启动流程]]")
        assertEquals("", links[0].target)
        assertEquals("启动流程", links[0].heading)
        assertEquals("启动流程", links[0].displayText())
    }

    @Test
    fun `path wikilink keeps slash`() {
        val links = wikiInlines("[[Java/Spring Boot]]")
        assertEquals("Java/Spring Boot", links[0].target)
    }

    @Test
    fun `md suffix wikilink keeps target`() {
        val links = wikiInlines("[[Claude-Code-Skills-Report.md]]")
        assertEquals("Claude-Code-Skills-Report.md", links[0].target)
    }

    @Test
    fun `multiple wikilinks in one paragraph`() {
        val links = wikiInlines("[[A]] 和 [[B|b]] 与 [[C#H]]")
        assertEquals(3, links.size)
        assertEquals(listOf("A", "B", "C"), links.map { it.target })
    }

    /* ── WikiLink 不能抢普通 Markdown 语义（§10）────────── */

    @Test
    fun `escaped wikilink stays text`() {
        val p = parseBlocks("转义 \\[[Not A Link]] 结束").first() as MdBlock.Paragraph
        assertTrue(p.inlines.none { it is MdInline.WikiLink })
        assertEquals("转义 [[Not A Link]] 结束", p.inlines.plainText())
    }

    @Test
    fun `wikilink inside code block not parsed`() {
        val blocks = parseBlocks("```\n[[Spring Boot]]\n```")
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("[[Spring Boot]]", code.code)
        assertTrue(blocks.none { it is MdBlock.Paragraph && it.inlines.any { i -> i is MdInline.WikiLink } })
    }

    @Test
    fun `wikilink inside inline code not parsed`() {
        val p = parseBlocks("行内 `[[Spring Boot]]` 代码").first() as MdBlock.Paragraph
        assertTrue(p.inlines.none { it is MdInline.WikiLink })
        assertTrue(p.inlines.any { it is MdInline.Code && it.text == "[[Spring Boot]]" })
    }

    @Test
    fun `unclosed double bracket stays text`() {
        val p = parseBlocks("未闭合 [[Spring Boot 单括号").first() as MdBlock.Paragraph
        assertTrue(p.inlines.none { it is MdInline.WikiLink })
        assertEquals("未闭合 [[Spring Boot 单括号", p.inlines.plainText())
    }

    @Test
    fun `escaped wikilink inside code block keeps literal`() {
        // 代码块中的 \[[ 原样保留（预处理不进围栏）
        val code = parseBlocks("```\n\\[[x]]\n```").first() as MdBlock.CodeBlock
        assertEquals("\\[[x]]", code.code)
    }

    /* ── Embed（§52）─────────────────────────────────────── */

    @Test
    fun `image embed becomes block`() {
        val blocks = parseBlocks("前文\n\n![[architecture.png]]\n\n后文")
        val embed = blocks[1] as MdBlock.ImageEmbed
        assertEquals("architecture.png", embed.target)
        assertNull(embed.widthPx)
    }

    @Test
    fun `image embed with size alias`() {
        val blocks = parseBlocks("![[Pasted image 20260408141852.png|650]]")
        val embed = blocks[0] as MdBlock.ImageEmbed
        assertEquals("Pasted image 20260408141852.png", embed.target)
        assertEquals(650, embed.widthPx)
    }

    @Test
    fun `folder image embed`() {
        val blocks = parseBlocks("![[folder/image.png]]")
        assertEquals("folder/image.png", (blocks[0] as MdBlock.ImageEmbed).target)
    }

    @Test
    fun `note embed becomes embedded note card block`() {
        val blocks = parseBlocks("![[Spring Boot]]")
        val embed = blocks[0] as MdBlock.EmbeddedNote
        assertEquals("Spring Boot", embed.target)
        assertNull(embed.heading)
    }

    @Test
    fun `note embed with heading`() {
        val blocks = parseBlocks("![[Spring Boot#启动流程]]")
        val embed = blocks[0] as MdBlock.EmbeddedNote
        assertEquals("Spring Boot", embed.target)
        assertEquals("启动流程", embed.heading)
    }

    @Test
    fun `unknown extension embed becomes file card`() {
        val blocks = parseBlocks("![[research.canvas|知识图谱]]")
        val embed = blocks[0] as MdBlock.EmbeddedFile
        assertEquals("research.canvas", embed.target)
        assertEquals("知识图谱", embed.label)
    }

    @Test
    fun `embed inside paragraph splits blocks`() {
        val blocks = parseBlocks("看这张 ![[a.png]] 还有 ![[Note.md]] 完")
        assertEquals(5, blocks.size) // 前段 / 图 / 中段 / 笔记 / 后段
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.ImageEmbed)
        assertTrue(blocks[2] is MdBlock.Paragraph)
        assertTrue(blocks[3] is MdBlock.EmbeddedNote)
        assertTrue(blocks[4] is MdBlock.Paragraph)
        assertEquals("看这张", (blocks[0] as MdBlock.Paragraph).inlines.plainText().trim())
        assertEquals("还有", (blocks[2] as MdBlock.Paragraph).inlines.plainText().trim())
        assertEquals("完", (blocks[4] as MdBlock.Paragraph).inlines.plainText().trim())
    }

    /* ── Callout（§51）───────────────────────────────────── */

    @Test
    fun `callout note with default title`() {
        val callout = parseBlocks("> [!NOTE]\n> 内容段").first() as MdBlock.Callout
        assertEquals("NOTE", callout.type)
        assertNull(callout.title)
        assertEquals(MdBlock.Callout.Kind.INFO, callout.kind)
        assertEquals(1, callout.blocks.size)
        assertEquals("内容段", (callout.blocks[0] as MdBlock.Paragraph).inlines.plainText())
    }

    @Test
    fun `callout warning kind`() {
        val callout = parseBlocks("> [!WARNING]\n> 内容").first() as MdBlock.Callout
        assertEquals(MdBlock.Callout.Kind.WARNING, callout.kind)
    }

    @Test
    fun `callout lowercase type case insensitive`() {
        val callout = parseBlocks("> [!note]\n> 内容").first() as MdBlock.Callout
        assertEquals("note", callout.type)
        assertEquals(MdBlock.Callout.Kind.INFO, callout.kind)
        val mixed = parseBlocks("> [!Note]\n> 内容").first() as MdBlock.Callout
        assertEquals("Note", mixed.type)
    }

    @Test
    fun `callout custom title`() {
        val callout = parseBlocks("> [!NOTE] 自定义标题\n> 内容").first() as MdBlock.Callout
        assertEquals("NOTE", callout.type)
        assertEquals("自定义标题", callout.title)
    }

    @Test
    fun `callout multiline content`() {
        val callout = parseBlocks("> [!TIP]\n> 第一段\n> 第二行\n>\n> 新段落").first() as MdBlock.Callout
        // lazy continuation 合并第一二行，空行分段
        assertEquals(2, callout.blocks.size)
        val first = (callout.blocks[0] as MdBlock.Paragraph).inlines.plainText()
        assertTrue(first.contains("第一段"))
        assertTrue(first.contains("第二行"))
        assertEquals("新段落", (callout.blocks[1] as MdBlock.Paragraph).inlines.plainText())
    }

    @Test
    fun `wikilink inside callout stays clickable inline`() {
        val callout = parseBlocks("> [!NOTE]\n> 可以参考 [[Spring Boot]]").first() as MdBlock.Callout
        val p = callout.blocks[0] as MdBlock.Paragraph
        val wiki = p.inlines.filterIsInstance<MdInline.WikiLink>()
        assertEquals(1, wiki.size)
        assertEquals("Spring Boot", wiki[0].target)
    }

    @Test
    fun `list inside callout`() {
        val callout = parseBlocks("> [!NOTE]\n> - 甲\n> - 乙").first() as MdBlock.Callout
        val list = callout.blocks[0] as MdBlock.BulletList
        assertEquals(listOf("甲", "乙"), list.items.map { it.inlines.plainText() })
    }

    @Test
    fun `callout fold markers parsed and ignored`() {
        val plus = parseBlocks("> [!NOTE]+\n> 内容").first() as MdBlock.Callout
        assertEquals("NOTE", plus.type)
        val minus = parseBlocks("> [!NOTE]-\n> 内容").first() as MdBlock.Callout
        assertEquals("NOTE", minus.type)
    }

    @Test
    fun `danger and success kinds`() {
        assertEquals(MdBlock.Callout.Kind.DANGER, parseBlocks("> [!BUG]\n> x").first().let { it as MdBlock.Callout }.kind)
        assertEquals(MdBlock.Callout.Kind.DANGER, parseBlocks("> [!DANGER]\n> x").first().let { it as MdBlock.Callout }.kind)
        assertEquals(MdBlock.Callout.Kind.SUCCESS, parseBlocks("> [!SUCCESS]\n> x").first().let { it as MdBlock.Callout }.kind)
        assertEquals(MdBlock.Callout.Kind.SUCCESS, parseBlocks("> [!DONE]\n> x").first().let { it as MdBlock.Callout }.kind)
        assertEquals(MdBlock.Callout.Kind.INFO, parseBlocks("> [!QUESTION]\n> x").first().let { it as MdBlock.Callout }.kind)
    }

    @Test
    fun `normal quote not misread as callout`() {
        val blocks = parseBlocks("> 普通引用 [! 不是标记] 文字")
        assertTrue(blocks[0] is MdBlock.Quote)
        val q = blocks[0] as MdBlock.Quote
        assertTrue((q.children[0] as MdBlock.Paragraph).inlines.plainText().contains("[!"))
    }

    @Test
    fun `quote starting with bracket but not callout`() {
        // [!x] 的 x 必须是字母开头；[!] 不合法
        val blocks = parseBlocks("> [!] 内容")
        assertTrue(blocks[0] is MdBlock.Quote)
    }

    /* ── 嵌套列表 / 引用（§43/§44）────────────────────────── */

    @Test
    fun `nested bullet list two levels`() {
        val blocks = parseBlocks("- 一级\n  - 二级甲\n  - 二级乙\n- 再一级")
        val list = blocks[0] as MdBlock.BulletList
        assertEquals(2, list.items.size)
        assertEquals("一级", list.items[0].inlines.plainText())
        val nested = list.items[0].children[0] as MdBlock.BulletList
        assertEquals(listOf("二级甲", "二级乙"), nested.items.map { it.inlines.plainText() })
    }

    @Test
    fun `nested ordered list`() {
        val blocks = parseBlocks("1. 一级\n   1. 二级")
        val list = blocks[0] as MdBlock.OrderedList
        assertEquals(1, list.items.size)
        assertTrue(list.items[0].children[0] is MdBlock.OrderedList)
    }

    @Test
    fun `nested quote`() {
        val blocks = parseBlocks("> 一级\n>> 二级")
        val quote = blocks[0] as MdBlock.Quote
        val inner = quote.children.first { it is MdBlock.Quote } as MdBlock.Quote
        assertEquals("二级", (inner.children[0] as MdBlock.Paragraph).inlines.plainText())
        // 外层同时保留一级文本
        assertTrue(quote.children.any { it is MdBlock.Paragraph && it.inlines.plainText() == "一级" })
    }

    @Test
    fun `task with nested list`() {
        val blocks = parseBlocks("- [x] 完成\n  - 子项")
        val tasks = blocks[0] as MdBlock.TaskList
        assertEquals(1, tasks.items.size)
        assertTrue(tasks.items[0].children[0] is MdBlock.BulletList)
    }

    /* ── Frontmatter（§53/§48）──────────────────────────── */

    @Test
    fun `frontmatter stripped and raw kept`() {
        val doc = MarkdownParser.parse("---\ntags: [笔记]\nstatus: 进行中\n---\n# 标题\n\n正文")
        assertEquals("---\ntags: [笔记]\nstatus: 进行中\n---", doc.frontmatter?.raw)
        assertEquals(2, doc.blocks.size) // hr 已剥离，首块是 H1
        assertEquals("标题", (doc.blocks[0] as MdBlock.Heading).inlines.plainText())
    }

    @Test
    fun `frontmatter body after hr keeps content`() {
        val doc = MarkdownParser.parse("---\na: 1\n---\n上文\n\n---\n\n下文")
        assertEquals("---\na: 1\n---", doc.frontmatter?.raw)
        val texts = doc.blocks.filterIsInstance<MdBlock.Paragraph>().map { it.inlines.plainText() }
        assertEquals(listOf("上文", "下文"), texts)
    }

    @Test
    fun `frontmatter raw content preserved exactly`() {
        val raw = "---\ntags:\n  - java\n  - spring\nstatus: draft\n---"
        val doc = MarkdownParser.parse("$raw\n\n正文")
        assertEquals(raw, doc.frontmatter?.raw)
    }

    @Test
    fun `unclosed frontmatter treated as body`() {
        val doc = MarkdownParser.parse("---\ntags: x\n\n# 标题")
        assertNull(doc.frontmatter)
        assertTrue(doc.blocks.any { it is MdBlock.Paragraph && it.inlines.plainText() == "tags: x" })
        assertTrue(doc.blocks.any { it is MdBlock.Heading })
    }

    @Test
    fun `no frontmatter`() {
        assertNull(MarkdownParser.parse("# 标题").frontmatter)
    }

    @Test
    fun `html block content preserved as text`() {
        assertEquals("<div>x</div>", firstParagraphText("<div>x</div>"))
    }

    /* ── 锚点 / 性能 ─────────────────────────────────────── */

    @Test
    fun `find heading anchor`() {
        val blocks = parseBlocks("# 开篇\n\n## 启动流程\n\n正文\n\n### 启动流程细化")
        assertEquals(1, findHeadingIndex(blocks, "启动流程"))
        assertEquals(0, findHeadingIndex(blocks, "开篇"))
        assertEquals(-1, findHeadingIndex(blocks, "不存在"))
    }

    @Test
    fun `find heading anchor case insensitive and trim`() {
        val blocks = parseBlocks("# Spring Boot 启动\n\n正文")
        assertEquals(0, findHeadingIndex(blocks, "spring boot 启动"))
        assertEquals(0, findHeadingIndex(blocks, "  Spring Boot 启动  "))
    }

    @Test
    fun `heading anchor strips inline style`() {
        val blocks = parseBlocks("## **加粗** 标题\n\n正文")
        assertEquals(0, findHeadingIndex(blocks, "加粗 标题"))
    }

    @Test
    fun `wikilink heavy document parses fast`() {
        val sb = StringBuilder("# 密集\n\n")
        repeat(2000) { i -> sb.append("第 ").append(i).append(" 行参考 [[Note ").append(i % 50).append("]] 与 [[A|别名]]。\n\n") }
        val start = System.currentTimeMillis()
        val doc = MarkdownParser.parse(sb.toString())
        val elapsed = System.currentTimeMillis() - start
        val wikiCount = doc.blocks.sumOf { b ->
            (b as? MdBlock.Paragraph)?.inlines?.count { it is MdInline.WikiLink } ?: 0
        }
        assertEquals(4000, wikiCount)
        assertTrue("耗时 ${elapsed}ms 应在 5s 内", elapsed < 5_000)
    }

    @Test
    fun `callout heavy document parses`() {
        val sb = StringBuilder()
        repeat(50) { i ->
            // 每个 callout 独立成块（V1 不解析 callout 内嵌套 callout，§24 记录为 Deviation）
            sb.append("> [!NOTE] 标题 ").append(i).append("\n> 内容 ").append(i).append("，参考 [[X").append(i).append("]]\n\n")
            sb.append("> [!WARNING]\n> 警告 ").append(i).append("\n\n")
        }
        val doc = MarkdownParser.parse(sb.toString())
        assertEquals(100, doc.blocks.count { it is MdBlock.Callout })
    }

    @Test
    fun `large document parses`() {
        val sb = StringBuilder()
        sb.append("# 大文档\n\n")
        for (i in 1..5000) {
            sb.append("第 ").append(i).append(" 行内容，包含 **加粗** 与 `代码`。\n\n")
            if (i % 50 == 0) sb.append("## 章节 ").append(i).append("\n\n")
        }
        val start = System.currentTimeMillis()
        val doc = MarkdownParser.parse(sb.toString())
        val elapsed = System.currentTimeMillis() - start
        assertTrue("解析块数=${doc.blocks.size}", doc.blocks.size > 5000)
        assertTrue("耗时 ${elapsed}ms 应在 5s 内", elapsed < 5_000)
    }

    @Test
    fun `soft break and hard break`() {
        val p = parseBlocks("第一行\n第二行").first() as MdBlock.Paragraph
        assertTrue(p.inlines.any { it is MdInline.SoftBreak })
        val p2 = parseBlocks("第一行  \n第二行").first() as MdBlock.Paragraph
        assertTrue(p2.inlines.any { it is MdInline.HardBreak })
    }
}
