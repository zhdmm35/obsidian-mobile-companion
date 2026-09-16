package com.obsidiancompanion.data.markdown

import com.obsidiancompanion.model.markdown.MdBlock
import com.obsidiancompanion.model.markdown.MdDocument
import com.obsidiancompanion.model.markdown.MdFrontmatter
import com.obsidiancompanion.model.markdown.MdInline
import com.obsidiancompanion.model.markdown.MdListItem
import com.obsidiancompanion.model.markdown.plainText
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** Obsidian 嵌入目标的扩展名分类（§34：extension 决定 Image / Note / Unknown；Tree kind 在解析期校正）。 */
internal val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "bmp", "avif")

/**
 * Markdown 解析管线（§44 + Phase 4 §6/§7）：
 * Source → frontmatter 剥离 → Obsidian 预处理（逃逸哨兵）
 * → commonmark-java → 自有 AST（WikiLink / Callout / Embed / 嵌套列表与引用）。
 * 纯 JVM、无 Android 依赖；未知节点兜底为文本，保证「不丢内容」（§47）。
 */
object MarkdownParser {

    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
            ),
        )
        .build()

    fun parse(source: String): MdDocument {
        val (frontmatter, body) = stripFrontmatter(source)
        val document = parser.parse(ObsidianSyntax.preprocess(body))
        return MdDocument(frontmatter, convertBlocks(document))
    }

    /* ── Frontmatter 预处理（§48：极窄实现，只剥离 + 保留原文）────── */

    internal fun stripFrontmatter(source: String): Pair<MdFrontmatter?, String> {
        val normalized = source.replace("\r\n", "\n")
        val lines = normalized.split("\n")
        if (lines.isEmpty() || lines.first() != "---") return null to normalized
        // 从第 2 行起找闭合 ---（首行自身就是 ---）
        var closeIndex = -1
        for (i in 1 until lines.size) {
            if (i > 200) break
            if (lines[i] == "---" || lines[i] == "...") {
                closeIndex = i
                break
            }
        }
        if (closeIndex < 0) return null to normalized // 无闭合：整体按正文处理（--- 为 hr）
        val raw = lines.subList(0, closeIndex + 1).joinToString("\n")
        return MdFrontmatter(raw) to lines.subList(closeIndex + 1, lines.size).joinToString("\n")
    }

    /* ── Block 转换 ─────────────────────────────────────────── */

    private fun convertBlocks(parent: Node): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        var child = parent.firstChild
        while (child != null) {
            out += convertBlockNode(child)
            child = child.next
        }
        return out
    }

    /** 单个 commonmark 块节点 → MdBlock 序列（Paragraph 可能因嵌入拆分出多块）。 */
    private fun convertBlockNode(child: Node): List<MdBlock> = when (child) {
        is Heading -> listOf(MdBlock.Heading(child.level, convertInlines(child)))
        is Paragraph -> convertParagraph(child)
        is BulletList -> listOf(convertList(child, ordered = false))
        is OrderedList -> listOf(convertList(child, ordered = true))
        is BlockQuote -> listOf(convertQuote(child))
        is FencedCodeBlock -> listOf(
            MdBlock.CodeBlock(
                language = child.info.trim().ifEmpty { null },
                code = ObsidianSyntax.restoreInCode(child.literal).trimEnd('\n'),
            ),
        )
        is IndentedCodeBlock -> listOf(MdBlock.CodeBlock(null, ObsidianSyntax.restoreInCode(child.literal).trimEnd('\n')))
        is ThematicBreak -> listOf(MdBlock.HorizontalRule)
        is TableBlock -> listOf(convertTable(child))
        is HtmlBlock -> listOf(MdBlock.Paragraph(listOf(MdInline.Text(child.literal.trim()))))
        is Document -> convertBlocks(child)
        else -> {
            // 未知节点兜底：收集纯文本成段，内容不丢
            val text = collectText(child).trim()
            if (text.isNotEmpty()) listOf(MdBlock.Paragraph(listOf(MdInline.Text(text)))) else emptyList()
        }
    }

    /** 段落：内联转换后，把 ![[embed]] 提升为独立块（§32：嵌入笔记不展开正文）。 */
    private fun convertParagraph(paragraph: Paragraph): List<MdBlock> {
        val inlines = convertInlines(paragraph)
        val first = paragraph.firstChild
        if (first is Image && first.next == null) {
            return listOf(MdBlock.Image(first.destination, altText(first)))
        }
        if (inlines.none { it is MdInline.WikiLink && it.embed }) {
            return listOf(MdBlock.Paragraph(inlines))
        }
        // 按嵌入位置切段：前文段落 / 嵌入块 / 后文段落
        val out = mutableListOf<MdBlock>()
        val buffer = mutableListOf<MdInline>()
        fun flush() {
            if (buffer.isNotEmpty()) {
                out += MdBlock.Paragraph(buffer.toList())
                buffer.clear()
            }
        }
        inlines.forEach { inline ->
            if (inline is MdInline.WikiLink && inline.embed) {
                flush()
                out += embedBlock(inline)
            } else {
                buffer += inline
            }
        }
        flush()
        return out
    }

    /** 嵌入块分类（§34）：图片扩展 → ImageEmbed；.md/无扩展 → EmbeddedNote；其他 → EmbeddedFile。 */
    private fun embedBlock(wiki: MdInline.WikiLink): MdBlock = when {
        wiki.target.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS ->
            MdBlock.ImageEmbed(wiki.target, ObsidianSyntax.aliasDigits(wiki.alias))
        wiki.target.substringAfterLast('.', "").lowercase() == "md" || !wiki.target.contains('.') ->
            MdBlock.EmbeddedNote(wiki.target, wiki.heading)
        else -> MdBlock.EmbeddedFile(wiki.target, wiki.alias)
    }

    /* ── Callout / Quote（§20-§24/§44）────────────────────────── */

    private fun convertQuote(quote: BlockQuote): MdBlock {
        val first = quote.firstChild
        if (first is Paragraph) {
            val headText = markerLineText(first)
            val match = ObsidianSyntax.CALLOUT.matchEntire(headText)
            if (match != null) {
                val type = match.groupValues[1]
                val customTitle = match.groupValues[3].trim().takeIf { it.isNotEmpty() }
                val blocks = mutableListOf<MdBlock>()
                // 标记行之后的同行内容（lazy continuation）成为首段
                val tail = tailAfterMarkerLine(first)
                if (tail.isNotEmpty()) blocks += MdBlock.Paragraph(tail)
                var child = first.next
                while (child != null) {
                    blocks += convertBlockNode(child)
                    child = child.next
                }
                return MdBlock.Callout(type, calloutKind(type), customTitle, blocks)
            }
        }
        return MdBlock.Quote(convertBlocks(quote))
    }

    /** 引用首段标记行（第一个 SoftLineBreak 之前）的纯文本。 */
    private fun markerLineText(paragraph: Paragraph): String {
        val sb = StringBuilder()
        var child: Node? = paragraph.firstChild
        while (child != null) {
            if (child is SoftLineBreak || child is HardLineBreak) break
            sb.append(when (child) {
                is Text -> ObsidianSyntax.restoreInText(child.literal)
                is Code -> child.literal
                else -> collectText(child)
            })
            child = child.next
        }
        return sb.toString().trimEnd()
    }

    /** 标记行之后的内联（同段落 lazy continuation 的后半段）。 */
    private fun tailAfterMarkerLine(paragraph: Paragraph): List<MdInline> {
        val tail = mutableListOf<MdInline>()
        var child: Node? = paragraph.firstChild
        var afterBreak = false
        while (child != null) {
            when {
                child is SoftLineBreak || child is HardLineBreak -> afterBreak = true
                afterBreak -> tail += convertInlineNode(child)
            }
            child = child.next
        }
        return if (tail.isEmpty()) emptyList() else splitWikiLinks(tail)
    }

    /** Callout 视觉档位（§21）：TIP/INFO 类、WARNING 类、DANGER 类、SUCCESS 类；未知类型归 INFO 但保留原文。 */
    internal fun calloutKind(type: String): MdBlock.Callout.Kind = when (type.uppercase()) {
        "WARNING", "CAUTION", "ATTENTION" -> MdBlock.Callout.Kind.WARNING
        "DANGER", "ERROR", "BUG", "FAILURE", "FAIL" -> MdBlock.Callout.Kind.DANGER
        "SUCCESS", "CHECK", "DONE", "CORRECT" -> MdBlock.Callout.Kind.SUCCESS
        else -> MdBlock.Callout.Kind.INFO // NOTE/INFO/TIP/ABSTRACT/SUMMARY/QUESTION/EXAMPLE/QUOTE/LINK/TODO…
    }

    /* ── 列表（§43：嵌套 2-3 层）───────────────────────────────── */

    private fun convertList(list: ListBlock, ordered: Boolean): MdBlock {
        val items = mutableListOf<ListItem>()
        var child = list.firstChild
        while (child != null) {
            if (child is ListItem) items += child
            child = child.next
        }
        fun taskMarker(item: ListItem): TaskListItemMarker? =
            item.firstChild as? TaskListItemMarker

        val hasTask = items.any { taskMarker(it) != null }
        return if (hasTask) {
            MdBlock.TaskList(
                items.map { item ->
                    MdBlock.TaskList.TaskItem(
                        done = taskMarker(item)?.isChecked == true,
                        inlines = firstParagraphInlines(item),
                        children = itemChildBlocks(item),
                    )
                },
            )
        } else if (ordered) {
            MdBlock.OrderedList((list as OrderedList).startNumber, items.map { listItem(it) })
        } else {
            MdBlock.BulletList(items.map { listItem(it) })
        }
    }

    private fun listItem(item: ListItem): MdListItem =
        MdListItem(firstParagraphInlines(item), itemChildBlocks(item))

    /** ListItem 首段内联；无段落（如纯嵌套列表）为空。 */
    private fun firstParagraphInlines(item: ListItem): List<MdInline> {
        var child: Node? = item.firstChild
        while (child != null) {
            if (child is Paragraph) return convertInlines(child)
            if (child is ListBlock || child is BlockQuote) break
            child = child.next
        }
        return emptyList()
    }

    /** ListItem 内嵌套块（子列表 / 引用 / 代码块 / 后续段落），保持文档顺序。 */
    private fun itemChildBlocks(item: ListItem): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        var child = item.firstChild
        var firstParagraphSkipped = false
        while (child != null) {
            when {
                child is Paragraph && !firstParagraphSkipped -> firstParagraphSkipped = true
                child is TaskListItemMarker -> Unit
                else -> out += convertBlockNode(child)
            }
            child = child.next
        }
        return out
    }

    private fun convertTable(table: TableBlock): MdBlock.Table {
        val headers = mutableListOf<List<MdInline>>()
        val rows = mutableListOf<List<List<MdInline>>>()
        var child = table.firstChild
        while (child != null) {
            when (child) {
                is TableHead -> {
                    var row = child.firstChild
                    while (row != null) {
                        if (row is TableRow) headers += rowCells(row)
                        row = row.next
                    }
                }
                is TableBody -> {
                    var row = child.firstChild
                    while (row != null) {
                        if (row is TableRow) rows += rowCells(row)
                        row = row.next
                    }
                }
            }
            child = child.next
        }
        return MdBlock.Table(headers, rows)
    }

    private fun rowCells(row: TableRow): List<List<MdInline>> {
        val cells = mutableListOf<List<MdInline>>()
        var cell = row.firstChild
        while (cell != null) {
            if (cell is TableCell) cells += convertInlines(cell)
            cell = cell.next
        }
        return cells
    }

    /* ── Inline 转换 ────────────────────────────────────────── */

    /** 单个 commonmark 内联节点 → MdInline 序列（Text 保留哨兵，待 splitWikiLinks 处理）。 */
    private fun convertInlineNode(node: Node): List<MdInline> = when (node) {
        is Text -> listOf(MdInline.Text(node.literal))
        is Code -> listOf(MdInline.Code(ObsidianSyntax.restoreInCode(node.literal)))
        is Emphasis -> listOf(MdInline.Italic(convertInlines(node)))
        is StrongEmphasis -> listOf(MdInline.Bold(convertInlines(node)))
        is org.commonmark.ext.gfm.strikethrough.Strikethrough -> listOf(MdInline.Strike(convertInlines(node)))
        is Link -> listOf(MdInline.Link(convertInlines(node), node.destination))
        is Image -> listOf(MdInline.Image(node.destination, altText(node)))
        is SoftLineBreak -> listOf(MdInline.SoftBreak)
        is HardLineBreak -> listOf(MdInline.HardBreak)
        is HtmlInline -> listOf(MdInline.Text(node.literal)) // 原文保留
        is TaskListItemMarker -> emptyList() // 任务勾选标记不产生内联内容（状态已在 TaskItem.done）
        else -> {
            // 未知内联节点：递归子节点（有则转），否则原文兜底
            if (node.firstChild != null) convertInlines(node) else listOf(MdInline.Text(collectText(node)))
        }
    }

    private fun convertInlines(parent: Node): List<MdInline> {
        val raw = mutableListOf<MdInline>()
        var child = parent.firstChild
        while (child != null) {
            raw += convertInlineNode(child)
            child = child.next
        }
        // 先合并相邻 Text（commonmark 可能把 ! 与 [[..]] 拆进不同 Text 节点），
        // 再切分 WikiLink / Embed（§9/§10）
        return splitWikiLinks(raw)
    }

    /** 合并相邻 Text 字面量后按 `[[..]]` / `![[..]]` 切分为 WikiLink 节点。 */
    internal fun splitWikiLinks(inlines: List<MdInline>): List<MdInline> {
        val merged = mergeAdjacentText(inlines)
        val out = mutableListOf<MdInline>()
        merged.forEach { inline ->
            if (inline is MdInline.Text) {
                out += ObsidianSyntax.splitText(inline.value)
            } else {
                out += inline
            }
        }
        return mergeAdjacentText(out)
    }

    /** 相邻 Text 合并（5000 行文档性能）。 */
    internal fun mergeAdjacentText(inlines: List<MdInline>): List<MdInline> {
        val merged = mutableListOf<MdInline>()
        inlines.forEach { inline ->
            val last = merged.lastOrNull()
            if (inline is MdInline.Text && last is MdInline.Text) {
                merged[merged.size - 1] = MdInline.Text(last.value + inline.value)
            } else {
                merged += inline
            }
        }
        return merged
    }

    private fun altText(image: Image): String {
        val sb = StringBuilder()
        var child = image.firstChild
        while (child != null) {
            sb.append(collectText(child))
            child = child.next
        }
        return sb.toString().trim()
    }

    /** 深度收集节点纯文本。 */
    private fun collectText(node: Node): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            when (n) {
                is Text -> sb.append(ObsidianSyntax.restoreInText(n.literal))
                is Code -> sb.append(n.literal)
                is SoftLineBreak -> sb.append(' ')
                is HardLineBreak -> sb.append('\n')
                is Image -> sb.append(altText(n))
                else -> {
                    var c = n.firstChild
                    while (c != null) {
                        walk(c)
                        c = c.next
                    }
                }
            }
        }
        walk(node)
        return sb.toString()
    }
}
