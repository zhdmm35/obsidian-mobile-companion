package com.obsidiancompanion.model.markdown

/**
 * 正式 Markdown AST（§44/§45）—— Parser 产出的唯一 Reader 数据模型。
 *
 * Phase 3：基础 CommonMark + GFM 表格/删除线/任务列表。
 * Phase 4：在同一模型上扩展 Obsidian 语法 ——
 *   - MdInline.WikiLink（[[Note|Alias#Heading]]，含 ![[...]] embed 形态）
 *   - MdBlock.Callout / EmbeddedNote / ImageEmbed / EmbeddedFile
 *   - 嵌套列表（MdListItem.children）与嵌套引用（Quote.children）
 * 不再另起第二套 AST / Renderer。
 */

data class MdDocument(
    val frontmatter: MdFrontmatter?,
    val blocks: List<MdBlock>,
)

/** 极窄 frontmatter 预处理（§48/§35）：只保留原始文本供 FileInfoSheet 展示，不做 YAML 解析、绝不改写。 */
data class MdFrontmatter(val raw: String)

/** 列表项：首段内联 + 嵌套子块（子列表 / 任务列表等，§43 支持常见 2-3 层）。 */
data class MdListItem(
    val inlines: List<MdInline>,
    val children: List<MdBlock> = emptyList(),
)

sealed interface MdBlock {

    /** H1-H6 全部忠实渲染，不跳过首个 H1（Phase 1 修正 #2）。 */
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock

    data class Paragraph(val inlines: List<MdInline>) : MdBlock

    data class BulletList(val items: List<MdListItem>) : MdBlock

    data class OrderedList(val startNumber: Int, val items: List<MdListItem>) : MdBlock

    data class TaskList(val items: List<TaskItem>) : MdBlock {
        data class TaskItem(
            val done: Boolean,
            val inlines: List<MdInline>,
            val children: List<MdBlock> = emptyList(),
        )
    }

    /** 引用块：children 为完整块（支持嵌套引用与内部列表，§44）。 */
    data class Quote(val children: List<MdBlock>) : MdBlock

    /**
     * Obsidian Callout（§20-§24）：`> [!TYPE] 标题` + 块内容。
     * kind 为归一化视觉档位（原型 co-note/co-warn + danger/success 扩展）；
     * type 保留原始类型文本（自定义类型不丢）。
     */
    data class Callout(
        val type: String,
        val kind: Kind,
        val title: String?,
        val blocks: List<MdBlock>,
    ) : MdBlock {
        enum class Kind { INFO, WARNING, DANGER, SUCCESS }
    }

    data class CodeBlock(val language: String?, val code: String) : MdBlock

    data class Table(
        val headers: List<List<MdInline>>,
        val rows: List<List<List<MdInline>>>,
    ) : MdBlock

    /** 独立成段的 Markdown 原生图片 ![alt](url)（Vault 内相对路径走附件解析，外部 URL 保留占位）。 */
    data class Image(val url: String, val alt: String?) : MdBlock

    /** Obsidian 图片嵌入 ![[a.png|650]]：widthPx 为 Obsidian 尺寸后缀（px，可空）。 */
    data class ImageEmbed(val target: String, val widthPx: Int?) : MdBlock

    /** Obsidian 笔记嵌入 ![[Note#Heading]]：V1 渲染为引用卡片，不展开正文（§32）。 */
    data class EmbeddedNote(val target: String, val heading: String?) : MdBlock

    /** 未知类型嵌入（.canvas/.mp3/…）：占位卡，不能 crash（§34）。label 为嵌入别名（如 canvas 显示名）。 */
    data class EmbeddedFile(val target: String, val label: String?) : MdBlock

    data object HorizontalRule : MdBlock
}

sealed interface MdInline {
    data class Text(val value: String) : MdInline
    data class Code(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Link(val children: List<MdInline>, val url: String) : MdInline
    data class Image(val url: String, val alt: String) : MdInline

    /**
     * Obsidian WikiLink（§9）。target 可为空串（同笔记锚点 `[[#H]]`）；
     * embed=true 表示 `![[...]]` 形态（Parser 会在块级拆分为 Embed 块，仅作中转）。
     * raw 保留原始文本兜底。
     */
    data class WikiLink(
        val target: String,
        val heading: String?,
        val alias: String?,
        val embed: Boolean,
        val raw: String,
    ) : MdInline

    data object SoftBreak : MdInline
    data object HardBreak : MdInline
}

/* ── 辅助 ──────────────────────────────────────────────────── */

/** 内联纯文本（锚点匹配 / 表格列宽估算 / 测试断言用）。SoftBreak 视为空格。 */
fun List<MdInline>.plainText(): String = joinToString("") { it.plainText() }

fun MdInline.plainText(): String = when (this) {
    is MdInline.Text -> value
    is MdInline.Code -> text
    is MdInline.Bold -> children.plainText()
    is MdInline.Italic -> children.plainText()
    is MdInline.Strike -> children.plainText()
    is MdInline.Link -> children.plainText()
    is MdInline.Image -> alt
    is MdInline.WikiLink -> displayText()
    MdInline.SoftBreak -> " "
    MdInline.HardBreak -> "\n"
}

/** WikiLink 显示文本（§14）：alias > "目标 › 标题" > 同笔记锚点文本。 */
fun MdInline.WikiLink.displayText(): String {
    alias?.let { return it }
    if (target.isEmpty()) return heading.orEmpty()
    return if (heading != null) "$target › $heading" else target
}

/** 在文档块列表中找标题锚点（§17：trim + 拉丁大小写不敏感 + Unicode 原文；精确优先，其次包含）。 */
fun findHeadingIndex(blocks: List<MdBlock>, anchor: String): Int {
    val target = anchor.trim()
    if (target.isEmpty()) return -1
    blocks.forEachIndexed { index, block ->
        if (block is MdBlock.Heading) {
            val text = block.inlines.plainText().trim()
            if (text.equals(target, ignoreCase = true)) return index
        }
    }
    blocks.forEachIndexed { index, block ->
        if (block is MdBlock.Heading && block.inlines.plainText().contains(target, ignoreCase = true)) return index
    }
    return -1
}
