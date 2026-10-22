package com.obsidiancompanion.feature.reader

import com.obsidiancompanion.data.metadata.entities.NoteUserMetadataEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPositionTest {
    private val saved = NoteUserMetadataEntity("o/r", "A.md", 100L, true, "sha1", 12, 48)

    @Test fun sameVersionRestoresPosition() {
        assertEquals(ReadingPosition(12, 48), restoreReadingPosition(saved, "sha1", 30))
    }

    @Test fun changedVersionAndMissingMetadataStartAtTop() {
        assertEquals(ReadingPosition(), restoreReadingPosition(saved, "sha2", 30))
        assertEquals(ReadingPosition(), restoreReadingPosition(null, "sha1", 30))
    }

    @Test fun invalidPositionsAreClampedWithoutKeepingAnOffsetForAnotherBlock() {
        assertEquals(ReadingPosition(4, 0), restoreReadingPosition(saved, "sha1", 5))
        assertEquals(ReadingPosition(), restoreReadingPosition(saved, "sha1", 0))
        assertEquals(ReadingPosition(12, 0), restoreReadingPosition(saved.copy(readingScrollOffset = -10), "sha1", 30))
    }
}
