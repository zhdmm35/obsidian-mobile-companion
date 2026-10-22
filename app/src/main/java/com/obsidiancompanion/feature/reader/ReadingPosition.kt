package com.obsidiancompanion.feature.reader

import com.obsidiancompanion.data.metadata.entities.NoteUserMetadataEntity

data class ReadingPosition(val itemIndex: Int = 0, val scrollOffset: Int = 0)

/** 段落索引只对同一正文版本有效；正文变化时从顶部开始，显式标题链接由阅读器优先定位。 */
internal fun restoreReadingPosition(metadata: NoteUserMetadataEntity?, sha: String, itemCount: Int): ReadingPosition {
    if (metadata?.readingBlobSha != sha || itemCount <= 0) return ReadingPosition()
    val index = metadata.readingItemIndex.coerceIn(0, itemCount - 1)
    val offset = if (index == metadata.readingItemIndex) metadata.readingScrollOffset.coerceAtLeast(0) else 0
    return ReadingPosition(index, offset)
}
