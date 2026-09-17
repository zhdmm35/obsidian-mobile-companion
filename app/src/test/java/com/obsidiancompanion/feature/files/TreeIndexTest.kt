package com.obsidiancompanion.feature.files

import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 目录索引：按父路径分组 / 文件夹在前 + 名称排序 / 根目录 null 键。 */
class TreeIndexTest {

    private fun entry(path: String, kind: EntryKind = EntryKind.MARKDOWN) = RepoEntryEntity(
        repoId = "o/r",
        path = path,
        name = path.substringAfterLast('/'),
        parentPath = path.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() },
        kind = kind,
        blobSha = "sha-$path",
        size = 100L,
        observedChangedAt = null,
    )

    @Test
    fun `groups children under their parent path`() {
        val index = indexByParent(listOf(entry("b.md"), entry("a.md"), entry("dir/c.md")))
        assertEquals(listOf("a.md", "b.md"), index[null]?.map { it.name })
        assertEquals(listOf("c.md"), index["dir"]?.map { it.name })
    }

    @Test
    fun `directories sort before files then by name`() {
        val index = indexByParent(
            listOf(
                entry("z.md"),
                entry("work", EntryKind.DIRECTORY),
                entry("a.md"),
                entry("archive", EntryKind.DIRECTORY),
            ),
        )
        assertEquals(listOf("archive", "work", "a.md", "z.md"), index[null]?.map { it.name })
    }

    @Test
    fun `empty tree yields empty index`() {
        assertEquals(emptyMap<String?, List<RepoEntryEntity>>(), indexByParent(emptyList()))
        assertNull(indexByParent(emptyList())[null])
    }
}
