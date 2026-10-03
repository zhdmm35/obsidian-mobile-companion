package com.obsidiancompanion.feature.reader

import com.obsidiancompanion.model.markdown.MdBlock
import com.obsidiancompanion.model.markdown.MdDocument
import com.obsidiancompanion.model.markdown.plainText

/** 目录保留块位置，重复标题也能准确跳转；不修改 Markdown 或已有链接锚点。 */
data class ReaderOutlineEntry(val blockIndex: Int, val level: Int, val title: String)

fun readerOutline(document: MdDocument): List<ReaderOutlineEntry> =
    document.blocks.mapIndexedNotNull { index, block ->
        val heading = block as? MdBlock.Heading ?: return@mapIndexedNotNull null
        val title = heading.inlines.plainText().trim()
        if (title.isEmpty()) null else ReaderOutlineEntry(index, heading.level, title)
    }

/** 正文自带同名首个 H1 时，收起外层大标题；正文块仍全部忠实渲染。 */
fun hasMatchingOpeningTitle(document: MdDocument, fileTitle: String): Boolean {
    val heading = document.blocks.firstOrNull() as? MdBlock.Heading ?: return false
    return heading.level == 1 && heading.inlines.plainText().trim().equals(fileTitle.trim(), ignoreCase = true)
}
