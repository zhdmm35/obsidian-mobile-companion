package com.obsidiancompanion.data.repository

import com.obsidiancompanion.data.github.RemoteTree
import com.obsidiancompanion.data.github.RemoteTreeEntry
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §72：Tree Diff 语义 —— Added/Changed/Deleted/Unchanged + observedChangedAt 规则 + 隐藏路径过滤。 */
class TreeDiffTest {

    private fun blob(path: String, sha: String, size: Long = 10) =
        RemoteTreeEntry(path, isDirectory = false, sha = sha, size = size)

    private fun treeDir(path: String, sha: String) =
        RemoteTreeEntry(path, isDirectory = true, sha = sha, size = null)

    private fun old(repoId: String = "o/r", path: String, sha: String, observed: Long? = null) =
        RepoEntryEntity(
            repoId = repoId, path = path, name = path.substringAfterLast('/'),
            parentPath = path.substringBeforeLast('/').ifEmpty { null },
            kind = EntryKind.MARKDOWN, blobSha = sha, size = 10, observedChangedAt = observed,
        )

    @Test
    fun `changed added deleted classified`() {
        val oldEntries = listOf(
            old(path = "A.md", sha = "sha1"),
            old(path = "B.md", sha = "sha2"),
            old(path = "D.md", sha = "shaD", observed = 111L),
        )
        val remote = RemoteTree(
            rootSha = "root",
            entries = listOf(blob("A.md", "sha3"), blob("C.md", "sha4"), blob("D.md", "shaD")),
            etag = null,
        )
        val now = 999L
        val r = TreeDiff.compute("o/r", oldEntries, remote, now)

        assertEquals(listOf("A.md"), r.changedPaths)
        assertEquals(listOf("C.md"), r.addedPaths)
        assertEquals(listOf("B.md"), r.deletedPaths)
        assertEquals(1, r.unchangedCount)
        assertTrue(r.hasChanges)

        val byPath = r.entries.associateBy { it.path }
        assertEquals(now, byPath.getValue("A.md").observedChangedAt)   // Changed → now
        assertEquals(now, byPath.getValue("C.md").observedChangedAt)   // 后续 Added → now
        assertEquals(111L, byPath.getValue("D.md").observedChangedAt)  // Unchanged 保留旧值
    }

    @Test
    fun `first load all observedChangedAt null`() {
        val remote = RemoteTree(
            rootSha = "root",
            entries = listOf(blob("A.md", "a"), blob("B.md", "b")),
            etag = null,
        )
        val r = TreeDiff.compute("o/r", emptyList(), remote, now = 999L)
        assertTrue(r.firstLoad)
        assertTrue(r.addedPaths.isEmpty())                              // 首次不算 Added
        r.entries.forEach { assertNull(it.observedChangedAt) }          // §23：不伪造「刚刚修改」
        assertEquals(2, r.entries.size)
    }

    @Test
    fun `hidden paths filtered at ingest`() {
        val remote = RemoteTree(
            rootSha = "root",
            entries = listOf(
                blob("工作/笔记.md", "s1"),
                treeDir(".obsidian", "t1"),
                blob(".obsidian/app.json", "s2"),
                blob(".trash/旧笔记.md", "s3"),
                blob(".DS_Store", "s4"),
                treeDir(".git", "t2"),
            ),
            etag = null,
        )
        val r = TreeDiff.compute("o/r", emptyList(), remote, 1L)
        assertEquals(listOf("工作/笔记.md"), r.entries.map { it.path })
    }

    @Test
    fun `kind classification by extension`() {
        val remote = RemoteTree(
            rootSha = "root",
            entries = listOf(
                blob("a.md", "1"), blob("b.MD", "2"), blob("c.png", "3"),
                blob("d.pdf", "4"), blob("e.canvas", "5"), blob("f.xlsx", "6"),
                treeDir("dir", "7"),
            ),
            etag = null,
        )
        val kinds = TreeDiff.compute("o/r", emptyList(), remote, 1L).entries
            .associate { it.name to it.kind }
        assertEquals(EntryKind.MARKDOWN, kinds["a.md"])
        assertEquals(EntryKind.MARKDOWN, kinds["b.MD"])
        assertEquals(EntryKind.IMAGE, kinds["c.png"])
        assertEquals(EntryKind.PDF, kinds["d.pdf"])
        assertEquals(EntryKind.CANVAS, kinds["e.canvas"])
        assertEquals(EntryKind.OTHER, kinds["f.xlsx"])
        assertEquals(EntryKind.DIRECTORY, kinds["dir"])
    }

    @Test
    fun `name and parentPath derived from path`() {
        val remote = RemoteTree(
            rootSha = "root",
            entries = listOf(blob("Java/Spring Boot.md", "s1"), blob("README.md", "s2")),
            etag = null,
        )
        val byPath = TreeDiff.compute("o/r", emptyList(), remote, 1L).entries.associateBy { it.path }
        val nested = byPath.getValue("Java/Spring Boot.md")
        assertEquals("Spring Boot.md", nested.name)
        assertEquals("Java", nested.parentPath)
        assertNull(byPath.getValue("README.md").parentPath)
    }

    @Test
    fun `directory sha change marks directory changed`() {
        val oldEntries = listOf(
            RepoEntryEntity("o/r", "Java", "Java", null, EntryKind.DIRECTORY, "tree-old", null, null),
            old(path = "Java/A.md", sha = "a1", observed = 5L),
        )
        val remote = RemoteTree(
            rootSha = "root",
            entries = listOf(treeDir("Java", "tree-new"), blob("Java/A.md", "a1")),
            etag = null,
        )
        val r = TreeDiff.compute("o/r", oldEntries, remote, now = 77L)
        assertEquals(listOf("Java"), r.changedPaths)
        assertEquals(77L, r.entries.first { it.path == "Java" }.observedChangedAt)
        assertEquals(5L, r.entries.first { it.path == "Java/A.md" }.observedChangedAt)
    }

    /* ── upsertEntries（增量入库行集）────────────────────── */

    @Test
    fun `upsertEntries firstLoad returns all entries`() {
        val remote = RemoteTree(
            rootSha = "root",
            entries = listOf(blob("A.md", "a"), blob("B.md", "b")),
            etag = null,
        )
        val r = TreeDiff.compute("o/r", emptyList(), remote, 999L)
        assertTrue(r.firstLoad)
        assertEquals(r.entries, r.upsertEntries())
    }

    @Test
    fun `upsertEntries returns only added and changed in remote order`() {
        val oldEntries = listOf(old(path = "A.md", sha = "sha1"), old(path = "B.md", sha = "sha2"))
        val remote = RemoteTree(
            rootSha = "root",
            entries = listOf(blob("A.md", "sha3"), blob("B.md", "sha2"), blob("C.md", "sha4")),
            etag = null,
        )
        val r = TreeDiff.compute("o/r", oldEntries, remote, 999L)
        assertEquals(listOf("A.md", "C.md"), r.upsertEntries().map { it.path })
    }

    @Test
    fun `upsertEntries empty when nothing added or changed`() {
        val oldEntries = listOf(old(path = "A.md", sha = "sha1"), old(path = "B.md", sha = "sha2"))
        val remote = RemoteTree(
            rootSha = "root",
            entries = listOf(blob("A.md", "sha1")), // B.md 删除，A.md 不变
            etag = null,
        )
        val r = TreeDiff.compute("o/r", oldEntries, remote, 999L)
        assertEquals(listOf("B.md"), r.deletedPaths)
        assertEquals(emptyList<RepoEntryEntity>(), r.upsertEntries())
    }
}
