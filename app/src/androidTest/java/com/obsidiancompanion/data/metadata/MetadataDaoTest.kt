package com.obsidiancompanion.data.metadata

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obsidiancompanion.data.metadata.entities.NoteUserMetadataEntity
import com.obsidiancompanion.data.metadata.entities.RecentSearchEntity
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.PendingEditEntity
import com.obsidiancompanion.data.metadata.entities.CaptureDraftEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** §74：收藏持久 / 最近阅读持久 / 最近搜索持久 / repo 作用域隔离 / Tree 刷新不碰用户 metadata。 */
@RunWith(AndroidJUnit4::class)
class MetadataDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() = db.close()

    /* ── 收藏 / 最近阅读（§41：identity = repoId + path，与 blobSha 无关）──── */

    @Test
    fun favoritePersistsAndMarkReadPreservesIt() = runTest {
        val dao = db.noteMetadataDao()
        dao.setFavorite("o/r", "Java/Spring Boot.md", true)
        dao.markRead("o/r", "Java/Spring Boot.md", readAt = 111L)

        val one = dao.observeOne("o/r", "Java/Spring Boot.md").first()!!
        assertTrue(one.isFavorite)
        assertEquals(111L, one.lastReadAt)
    }

    @Test
    fun setFavoritePreservesLastRead() = runTest {
        val dao = db.noteMetadataDao()
        dao.markRead("o/r", "A.md", readAt = 222L)
        dao.setFavorite("o/r", "A.md", true)
        val one = dao.observeOne("o/r", "A.md").first()!!
        assertTrue(one.isFavorite)
        assertEquals(222L, one.lastReadAt)
    }

    @Test
    fun recentReadOrderedDesc() = runTest {
        val dao = db.noteMetadataDao()
        dao.markRead("o/r", "A.md", 100L)
        dao.markRead("o/r", "B.md", 300L)
        dao.markRead("o/r", "C.md", 200L)
        val reads = dao.observeRecentRead("o/r", 10).first()
        assertEquals(listOf("B.md", "C.md", "A.md"), reads.map { it.path })
    }

    /* ── repo 作用域隔离（§68-70）────────────────────────────── */

    @Test
    fun repoScopeIsolation() = runTest {
        val dao = db.noteMetadataDao()
        dao.setFavorite("repoA", "Java.md", true)
        dao.setFavorite("repoB", "Java.md", false)

        assertTrue(dao.observeOne("repoA", "Java.md").first()!!.isFavorite)
        assertFalse(dao.observeOne("repoB", "Java.md").first()!!.isFavorite)
        assertEquals(0, dao.observeFavorites("repoB").first().size)
        assertEquals(1, dao.observeFavorites("repoA").first().size)
    }

    /* ── Tree 刷新不覆盖用户 metadata；远端删除不删 metadata（§43）──────── */

    @Test
    fun treeReplaceDoesNotTouchUserMetadata() = runTest {
        val entries = db.repoEntryDao()
        val dao = db.noteMetadataDao()

        entries.insertAll(listOf(entry("o/r", "A.md", "sha-old")))
        dao.setFavorite("o/r", "A.md", true)

        // 模拟 Tree refresh：整表替换
        db.withTransaction {
            entries.deleteByRepo("o/r")
            entries.insertAll(listOf(entry("o/r", "A.md", "sha-new")))
        }

        assertTrue(dao.observeOne("o/r", "A.md").first()!!.isFavorite)
        assertEquals("sha-new", entries.get("o/r", "A.md")!!.blobSha)
    }

    @Test
    fun deletedRemoteNoteKeepsMetadata() = runTest {
        val entries = db.repoEntryDao()
        val dao = db.noteMetadataDao()
        entries.insertAll(listOf(entry("o/r", "A.md", "sha1")))
        dao.setFavorite("o/r", "A.md", true)

        // 远端删除 → Tree 只是不再包含该 path；metadata 保留（UI 侧按当前 Tree 过滤）
        db.withTransaction {
            entries.deleteByRepo("o/r")
            entries.insertAll(emptyList())
        }
        assertTrue(dao.observeOne("o/r", "A.md").first()!!.isFavorite)
    }

    /* ── Tree 缓存观测 ──────────────────────────────────────── */

    @Test
    fun treeObserveAllAndState() = runTest {
        val entries = db.repoEntryDao()
        entries.insertAll(
            listOf(
                entry("o/r", "Java", "tree1", kind = EntryKind.DIRECTORY),
                entry("o/r", "Java/A.md", "blob1"),
            ),
        )
        val all = entries.observeAll("o/r").first()
        assertEquals(2, all.size)
        assertEquals(EntryKind.DIRECTORY, all.first { it.path == "Java" }.kind)
        assertEquals("Java", all.first { it.path == "Java/A.md" }.parentPath)
    }

    /* ── 最近搜索（§28）────────────────────────────────────── */

    @Test
    fun recentSearchUpsertDedupesAndReorders() = runTest {
        val dao = db.recentSearchDao()
        dao.record("o/r", "Spring", 100L)
        dao.record("o/r", "Git", 200L)
        dao.record("o/r", "Spring", 300L) // 重复 query → 只更新时间

        val recent = dao.observeRecent("o/r", 10).first()
        assertEquals(listOf("Spring", "Git"), recent.map { it.query })
    }

    @Test
    fun recentSearchScopedByRepo() = runTest {
        val dao = db.recentSearchDao()
        dao.record("repoA", "Spring", 100L)
        dao.record("repoB", "Spring", 100L)
        assertEquals(1, dao.observeRecent("repoA", 10).first().size)
    }

    @Test
    fun draftsObserveAllReposOrderedByTime_andDeleteOnlyExactSnapshot() = runTest {
        val dao = db.pendingEditDao()
        val older = PendingEditEntity("repoA", "A.md", "sha1", "old", 100L)
        val newer = PendingEditEntity("repoB", "A.md", "sha2", "other repo", 200L)
        dao.upsert(older)
        dao.upsert(newer)
        assertEquals(listOf(newer, older), dao.observeAll().first())
        val changed = older.copy(content = "modified", updatedAt = 300L)
        dao.upsert(changed)
        assertEquals(0, dao.deleteIfUnchanged(older.repoId, older.path, older.baseSha, older.content, older.updatedAt))
        assertEquals(changed, dao.get(older.repoId, older.path))
        assertEquals(1, dao.deleteIfUnchanged(changed.repoId, changed.path, changed.baseSha, changed.content, changed.updatedAt))
        assertEquals(listOf(newer), dao.observeAll().first())
    }

    @Test
    fun draftQueriesOnlyReturnShortPreviewsAndMetadataUpdatesKeepBody() = runTest {
        val captures = db.captureDraftDao()
        val body = "😀正文".repeat(10_000)
        val row = CaptureDraftEntity("one", body, "old", "", 100L)
        captures.insert(row)
        assertEquals(1, captures.observeCount().first())
        val preview = captures.observeSummaries().first().single().preview
        assertEquals(160, preview.codePointCount(0, preview.length))
        assertTrue(body.startsWith(preview))
        captures.update(row.id, "old", "", 200L)
        assertEquals(row, captures.get(row.id))
        captures.update(row.id, "new", "Inbox", 300L)
        assertEquals(body, captures.get(row.id)!!.text)
        assertEquals(0, captures.deleteIfUnchanged(row.id, row.text, row.name, row.folder, row.updatedAt))
        val changed = captures.get(row.id)!!
        assertEquals(1, captures.deleteIfUnchanged(changed.id, changed.text, changed.name, changed.folder, changed.updatedAt))
        assertEquals(0, captures.update(row.id, "new", "", 400L))
        db.pendingEditDao().upsert(PendingEditEntity("o/r", "A.md", "sha", body, 1L))
        assertEquals(1, db.pendingEditDao().observeCount().first())
        assertEquals(preview, db.pendingEditDao().observeSummaries().first().single().preview)
    }

    @Test
    fun migrationFromVersion2KeepsFavoritesSearchTreeAndPendingEdits() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-perf-test.db"
        context.deleteDatabase(name)
        try {
            // v2 与 v3 的既有表结构相同；移除新增表并降为 v2，走真实 Room 升级和 schema 验证。
            Room.databaseBuilder(context, AppDatabase::class.java, name).build().let { old ->
                try {
                    old.repoEntryDao().insertAll(listOf(entry("o/r", "A.md", "sha")))
                    old.noteMetadataDao().setFavorite("o/r", "A.md", true)
                    old.recentSearchDao().record("o/r", "keyword", 2L)
                    old.pendingEditDao().upsert(PendingEditEntity("o/r", "A.md", "sha", "edited", 3L))
                } finally { old.close() }
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("DROP TABLE capture_drafts")
                old.version = 2
            }
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_2_3).build().let { upgraded ->
                    try {
                        assertEquals("sha", upgraded.repoEntryDao().get("o/r", "A.md")!!.blobSha)
                        assertTrue(upgraded.noteMetadataDao().observeOne("o/r", "A.md").first()!!.isFavorite)
                        assertEquals("keyword", upgraded.recentSearchDao().observeRecent("o/r", 10).first().single().query)
                        assertEquals("edited", upgraded.pendingEditDao().get("o/r", "A.md")!!.content)
                        assertEquals(0, upgraded.captureDraftDao().observeCount().first())
                        upgraded.captureDraftDao().insert(CaptureDraftEntity("one", "body", "name", "", 4L))
                        assertEquals(1, upgraded.captureDraftDao().observeCount().first())
                    } finally { upgraded.close() }
                }
        } finally { context.deleteDatabase(name) }
    }

    private fun entry(repoId: String, path: String, sha: String, kind: EntryKind = EntryKind.MARKDOWN) =
        RepoEntryEntity(
            repoId = repoId,
            path = path,
            name = path.substringAfterLast('/'),
            parentPath = path.substringBeforeLast('/').ifEmpty { null },
            kind = kind,
            blobSha = sha,
            size = 10L,
            observedChangedAt = null,
        )
}
