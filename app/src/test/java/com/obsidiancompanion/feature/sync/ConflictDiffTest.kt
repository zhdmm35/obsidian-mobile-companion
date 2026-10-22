package com.obsidiancompanion.feature.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictDiffTest {
    @Test fun separatesAddedRemovedAndUnchangedLines() {
        val diff = conflictLineDiff("title\nremote\nshared\nend", "title\nmine\nshared\nextra\nend")
        assertEquals(listOf(
            ConflictDiffLine("title", ConflictLineKind.UNCHANGED),
            ConflictDiffLine("remote", ConflictLineKind.REMOTE_ONLY),
            ConflictDiffLine("mine", ConflictLineKind.MINE_ONLY),
            ConflictDiffLine("shared", ConflictLineKind.UNCHANGED),
            ConflictDiffLine("extra", ConflictLineKind.MINE_ONLY),
            ConflictDiffLine("end", ConflictLineKind.UNCHANGED),
        ), diff.lines)
        assertFalse(diff.simplified)
    }

    @Test fun emptyAndIdenticalVersions() {
        assertTrue(conflictLineDiff("", "").lines.isEmpty())
        assertEquals(listOf(ConflictDiffLine("中文 🚀", ConflictLineKind.MINE_ONLY)), conflictLineDiff("", "中文 🚀").lines)
        assertEquals(listOf(ConflictDiffLine("one", ConflictLineKind.REMOTE_ONLY)), conflictLineDiff("one", "").lines)
        assertTrue(conflictLineDiff("a\nb\n", "a\nb\n").lines.all { it.kind == ConflictLineKind.UNCHANGED })
    }

    @Test fun repeatedLinesAndDifferentLineEndingsStillReconstructBothVersions() {
        val remote = "a\nx\na\ny\na"
        val mine = "a\ry\ra\rx\ra"
        val diff = conflictLineDiff(remote, mine)
        assertEquals(remote.lines(), diff.lines.filter { it.kind != ConflictLineKind.MINE_ONLY }.map { it.text })
        assertEquals(mine.lines(), diff.lines.filter { it.kind != ConflictLineKind.REMOTE_ONLY }.map { it.text })
    }

    @Test fun longChangedSectionsUseBoundedFallbackWithoutLosingEitherVersion() {
        val remote = "title\n" + (1..1000).joinToString("\n") { "remote $it" } + "\nend"
        val mine = "title\n" + (1..1000).joinToString("\n") { "mine $it" } + "\nend"
        val diff = conflictLineDiff(remote, mine)
        assertTrue(diff.simplified)
        assertEquals(remote.lines(), diff.lines.filter { it.kind != ConflictLineKind.MINE_ONLY }.map { it.text })
        assertEquals(mine.lines(), diff.lines.filter { it.kind != ConflictLineKind.REMOTE_ONLY }.map { it.text })
    }
}
