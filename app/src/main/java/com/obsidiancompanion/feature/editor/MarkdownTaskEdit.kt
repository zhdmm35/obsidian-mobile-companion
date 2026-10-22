package com.obsidiancompanion.feature.editor

import com.obsidiancompanion.model.markdown.MdBlock
import com.obsidiancompanion.model.markdown.MdFrontmatter

/** 仅替换勾选标记的一个字符，保留原文换行、缩进及 Frontmatter。失效的位置拒绝修改。 */
internal fun toggleMarkdownTask(source: String, frontmatter: MdFrontmatter?, task: MdBlock.TaskList.TaskItem): String? {
    if (!task.isTask) return null
    val line = task.sourceLine ?: return null
    val column = task.sourceColumn ?: return null
    if (line < 0 || column < 0) return null
    val skipped = frontmatter?.raw?.let { it.count { c -> c == '\n' } + 1 } ?: 0
    var start = 0
    repeat(skipped) {
        val newline = source.indexOf('\n', start)
        if (newline < 0) return null
        start = newline + 1
    }
    repeat(line) {
        val newline = source.indexOfAny(charArrayOf('\r', '\n'), start)
        if (newline < 0) return null
        start = newline + if (source[newline] == '\r' && source.getOrNull(newline + 1) == '\n') 2 else 1
    }
    val offset = start + column
    val end = source.indexOfAny(charArrayOf('\r', '\n'), start).let { if (it < 0) source.length else it }
    if (offset + 3 > end || source[offset] != '[' || source[offset + 2] != ']') return null
    val mark = source[offset + 1]
    if (mark !in " xX" || (mark != ' ') != task.done) return null
    return source.replaceRange(offset + 1, offset + 2, if (task.done) " " else "x")
}
