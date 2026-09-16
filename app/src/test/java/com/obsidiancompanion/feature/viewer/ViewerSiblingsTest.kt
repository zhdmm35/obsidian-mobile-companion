package com.obsidiancompanion.feature.viewer

import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 同目录图片列表：仅 IMAGE / 按名称排序 / 根目录 parentPath=null / 当前图缺失时退化为仅自身。 */
class ViewerSiblingsTest {

    private fun entry(path: String, kind: EntryKind): RepoEntryEntity {
        val name = path.substringAfterLast('/')
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        return RepoEntryEntity(
            repoId = "o/r",
            path = path,
            name = name,
            parentPath = parent.takeIf { it.isNotEmpty() && it != path },
            kind = kind,
            blobSha = "sha-$path",
            size = 100L,
            observedChangedAt = null,
        )
    }

    private val tree = listOf(
        entry("assets/b.png", EntryKind.IMAGE),
        entry("assets/a.png", EntryKind.IMAGE),
        entry("assets/封面 c.jpg", EntryKind.IMAGE),
        entry("assets/notes.md", EntryKind.MARKDOWN),
        entry("assets/deep/d.png", EntryKind.IMAGE),
        entry("root.png", EntryKind.IMAGE),
    )

    @Test
    fun `same folder images sorted by name`() {
        assertEquals(
            listOf("assets/a.png", "assets/b.png", "assets/封面 c.jpg"),
            siblingImagePaths(tree, "assets/b.png"),
        )
    }

    @Test
    fun `root level image has only root siblings`() {
        assertEquals(listOf("root.png"), siblingImagePaths(tree, "root.png"))
    }

    @Test
    fun `nested folder does not leak into parent listing`() {
        assertEquals(listOf("assets/deep/d.png"), siblingImagePaths(tree, "assets/deep/d.png"))
    }

    @Test
    fun `current path missing from tree degrades to itself`() {
        assertEquals(listOf("gone.png"), siblingImagePaths(tree, "gone.png"))
    }

    @Test
    fun `empty tree degrades to itself`() {
        assertEquals(listOf("a.png"), siblingImagePaths(emptyList(), "a.png"))
    }
}
