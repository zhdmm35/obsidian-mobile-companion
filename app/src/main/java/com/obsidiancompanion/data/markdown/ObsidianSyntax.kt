package com.obsidiancompanion.data.markdown

import com.obsidiancompanion.model.markdown.MdInline

/**
 * Obsidian 语法层（Phase 4 §7/§9/§10/§20）：
 * WikiLink / Embed / Callout 在 parser 阶段转为明确 AST 节点，
 * 不在 ReaderScreen 识别字符串。
 *
 * 逃逸处理：`\[[x]]` 必须保持普通文本（§10）。
 * CommonMark 会把 `\[` 与后续未匹配的括号合并进同一个 Text 节点，
 * 事后无法区分，因此在预处理期用私有区哨兵字符标记逃逸括号，
 * 解析完成后原样还原 —— 哨兵不落盘、不进 AST。
 */
internal object ObsidianSyntax {

    /** U+E000 私有区字符：真实 Vault 中出现概率可忽略；仅存在于 Parser 内部中间态。 */
    private const val ESCAPED_BRACKET = "\uE000"

    /** `[[target#heading|alias]]` —— 不含嵌套方括号、target 非空（允许 `[[#heading]]`）。 */
    private val WIKI = Regex("(!?)(\\[\\[([^\\[\\]\\n]+?)\\]\\])")

    /** Callout 首行标记：`> [!TYPE]` / `> [!TYPE]+` / `> [!TYPE]-` / `> [!TYPE] 自定义标题`。 */
    internal val CALLOUT = Regex("^\\[!([A-Za-z][A-Za-z0-9_-]*)]([+-])?(?:\\s+(.*))?$")

    /* ── 预处理：标记逃逸括号（代码围栏内不动，原文照排）───────── */

    internal fun preprocess(source: String): String {
        if (!source.contains("\\[[")) return source
        val out = StringBuilder(source.length)
        var fenceMarker: String? = null // "```" 或 "~~~"
        source.split("\n").forEachIndexed { index, original ->
            var line = original
            val t = line.trimStart()
            val marker = fenceMarker
            if (marker != null) {
                // 围栏内：原文保留；遇到闭合围栏则退出
                if (t.startsWith(marker)) fenceMarker = null
            } else if (t.startsWith("```") || t.startsWith("~~~")) {
                fenceMarker = t.take(3)
            } else {
                line = line.replace("\\[[", ESCAPED_BRACKET + "[[")
            }
            if (index > 0) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }

    /** 解析完成后还原哨兵：Text 中哨兵代表一个真实 `[`（来自 `\[`），代码字面量中还原为 `\[`。 */
    internal fun restoreInText(literal: String): String = literal.replace(ESCAPED_BRACKET, "[")

    internal fun restoreInCode(literal: String): String = literal.replace(ESCAPED_BRACKET, "\\[")

    /* ── WikiLink 目标解析（§9/§13/§14）──────────────────────── */

    internal data class WikiParts(
        val target: String,
        val heading: String?,
        val alias: String?,
        val embed: Boolean,
        val raw: String,
    )

    /**
     * `Spring Boot#启动流程|Spring` → (target=Spring Boot, heading=启动流程, alias=Spring)。
     * 尺寸别名（图片 `|650`）由调用方按需读取 aliasDigits()。
     */
    internal fun parseWikiTarget(inner: String, embed: Boolean, raw: String): WikiParts {
        val body = inner.trim()
        val alias: String? = if (body.contains('|')) body.substringAfter('|').trim().takeIf { it.isNotEmpty() } else null
        val linkPart = if (body.contains('|')) body.substringBefore('|').trim() else body
        // 同笔记锚点：[[#H]] → target 为空；普通链接先按第一个 # 拆 heading
        val target: String
        val heading: String?
        if (linkPart.startsWith("#")) {
            target = ""
            heading = linkPart.removePrefix("#").trim().takeIf { it.isNotEmpty() }
        } else {
            target = linkPart.substringBefore('#').trim()
            heading = if (linkPart.contains('#')) linkPart.substringAfter('#').trim().takeIf { it.isNotEmpty() } else null
        }
        return WikiParts(target, heading, alias, embed, raw)
    }

    /** 图片嵌入的尺寸后缀：alias 为纯数字时视为 px 宽（Obsidian `![[a.png|650]]`）。 */
    internal fun aliasDigits(alias: String?): Int? = alias?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    /* ── Text 节点切分：`[[..]]` / `![[..]]` → WikiLink 节点（§10）── */

    /**
     * 把一个 Text 字面量切成 Text / WikiLink 序列。
     * - 哨兵后的 `[[` 不算链接（逃逸，§10）
     * - 非法目标（空括号、控制字符）保持原文本
     */
    internal fun splitText(literal: String): List<MdInline> {
        if (!literal.contains("[[") && !literal.contains(ESCAPED_BRACKET)) {
            return listOf(MdInline.Text(literal))
        }
        val out = mutableListOf<MdInline>()
        var i = 0
        val n = literal.length
        val plain = StringBuilder()
        while (i < n) {
            val c = literal[i]
            when {
                c == ESCAPED_BRACKET[0] && literal.startsWith(ESCAPED_BRACKET + "[[", i) -> {
                    // 逃逸：`\[` 输出真实 `[` 并消费它配对的那个括号，
                    // 剩余的单 `[..]]` 不再构成 WikiLink（§10）
                    plain.append('[')
                    i += ESCAPED_BRACKET.length + 1
                }
                (c == '[' && literal.startsWith("[[", i)) || (c == '!' && literal.startsWith("![[", i)) -> {
                    val match = WIKI.find(literal, i)
                    if (match != null && match.range.first == i) {
                        val (bang, bracketText, inner) = match.destructured
                        if (plain.isNotEmpty()) {
                            out += MdInline.Text(plain.toString())
                            plain.clear()
                        }
                        val raw = if (bang.isEmpty()) bracketText else "!$bracketText"
                        out += wikiInline(inner, embed = bang.isNotEmpty(), raw = raw)
                        i = match.range.last + 1
                    } else {
                        plain.append(c)
                        i += 1
                    }
                }
                else -> {
                    // 预处理不变量：哨兵只会出现在 [[ 前；兜底按真实 `[` 输出，绝不外泄
                    plain.append(if (c == ESCAPED_BRACKET[0]) '[' else c)
                    i += 1
                }
            }
        }
        if (plain.isNotEmpty()) out += MdInline.Text(plain.toString())
        return out
    }

    private fun wikiInline(inner: String, embed: Boolean, raw: String): MdInline {
        val parts = parseWikiTarget(inner, embed, raw)
        return MdInline.WikiLink(parts.target, parts.heading, parts.alias, parts.embed, parts.raw)
    }
}
