package com.obsidiancompanion.data.repository

import com.obsidiancompanion.data.markdown.MarkdownParser
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineFolderManagerTest {
    @Test
    fun `folder state distinguishes download progress and retryable failure`() {
        assertEquals("未下载", OfflineFolderManager.FolderState(0, 2, 0).label)
        assertEquals("下载中 1/2", OfflineFolderManager.FolderState(1, 2, 10, OfflineFolderManager.Progress(1, 2, 0)).label)
        assertEquals("部分失败 1/2", OfflineFolderManager.FolderState(1, 2, 10, failed = true).label)
        assertEquals("已下载", OfflineFolderManager.FolderState(2, 2, 20).label)
    }

    @Test
    fun `only selected folder descendants and readable types are downloaded`() {
        fun entry(path: String, kind: EntryKind) = RepoEntryEntity(
            repoId = "o/r", path = path, name = path.substringAfterLast('/'),
            parentPath = path.substringBeforeLast('/'), kind = kind,
            blobSha = "abc123", size = null, observedChangedAt = null,
        )
        val tree = listOf(
            entry("Work/note.md", EntryKind.MARKDOWN),
            entry("Work/Child/photo.png", EntryKind.IMAGE),
            entry("Work/file.pdf", EntryKind.PDF),
            entry("Workspace/other.md", EntryKind.MARKDOWN),
        )
        assertEquals(listOf("Work/note.md", "Work/Child/photo.png"), offlineFolderMembers("Work", tree).map { it.path })
    }

    @Test
    fun `image targets include nested markdown images and exclude remote URLs`() {
        val doc = MarkdownParser.parse("""
            ![[inside.png]]

            > ![quote](../Assets/quote.png)

            - **![inline](nested.png)**

            ![remote](https://example.com/image.png)
        """.trimIndent())
        assertEquals(setOf("inside.png", "../Assets/quote.png", "nested.png"), imageTargets(doc))
    }
}
