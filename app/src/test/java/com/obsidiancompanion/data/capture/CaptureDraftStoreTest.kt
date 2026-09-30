package com.obsidiancompanion.data.capture

import android.content.SharedPreferences
import com.obsidiancompanion.data.metadata.dao.CaptureDraftDao
import com.obsidiancompanion.data.metadata.entities.CaptureDraftEntity
import com.obsidiancompanion.data.metadata.entities.CaptureDraftSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CaptureDraftStoreTest {
    private class MemoryDao : CaptureDraftDao {
        val rows = MutableStateFlow<Map<String, CaptureDraftEntity>>(emptyMap())
        var fullReads = 0
        override fun observeSummaries() = rows.map { map -> map.values
            .sortedWith(compareByDescending<CaptureDraftEntity> { it.updatedAt }.thenBy { it.id })
            .map { CaptureDraftSummary(it.id, it.name, it.folder, it.text.take(160), it.updatedAt) } }
        override fun observeCount() = rows.map { it.size }
        override suspend fun get(id: String): CaptureDraftEntity? { fullReads++; return rows.value[id] }
        override suspend fun insert(draft: CaptureDraftEntity) {
            check(draft.id !in rows.value)
            rows.value = rows.value + (draft.id to draft)
        }
        override suspend fun importLegacy(drafts: List<CaptureDraftEntity>) {
            drafts.forEach { if (it.id !in rows.value) insert(it) }
        }
        override suspend fun update(id: String, name: String, folder: String, updatedAt: Long): Int {
            val old = rows.value[id] ?: return 0
            rows.value = rows.value + (id to old.copy(name = name, folder = folder,
                updatedAt = if (old.name == name && old.folder == folder) old.updatedAt else updatedAt))
            return 1
        }
        override suspend fun deleteIfUnchanged(id: String, text: String, name: String, folder: String, updatedAt: Long): Int {
            if (rows.value[id] != CaptureDraftEntity(id, text, name, folder, updatedAt)) return 0
            rows.value = rows.value - id
            return 1
        }
    }

    @Test fun savedDraftRestoresIdentityAndContent() = runTest {
        val prefs = MemoryPreferences(); val dao = MemoryDao()
        val saved = CaptureDraftStore(prefs, dao).create("分享正文", "名字", "Inbox")
        assertEquals(saved, CaptureDraftStore(prefs, dao).find(saved.id))
    }

    @Test fun multipleSharesDoNotOverwriteEvenIdenticalText() = runTest {
        val store = CaptureDraftStore(MemoryPreferences(), MemoryDao())
        val first = store.create("正文", "笔记")
        val second = store.create("正文", "笔记", "Inbox")
        assertNotEquals(first.id, second.id)
        assertEquals(2, store.count.first())
        assertEquals(setOf(first.id, second.id), store.drafts.first().map { it.id }.toSet())
    }

    @Test fun successfulUploadOnlyClearsItsOwnUnchangedDraft() = runTest {
        val store = CaptureDraftStore(MemoryPreferences(), MemoryDao())
        val uploaded = store.create("正文", "笔记")
        val other = store.create("其他", "其他笔记")
        assertTrue(store.clearIfUnchanged(uploaded))
        assertEquals(listOf(other.id), store.drafts.first().map { it.id })
    }

    @Test fun completingOlderUploadDoesNotDeleteChangedDraft() = runTest {
        val store = CaptureDraftStore(MemoryPreferences(), MemoryDao())
        val uploaded = store.create("正文", "旧名字")
        assertTrue(store.update(uploaded.id, "新名字", "Inbox"))
        assertFalse(store.clearIfUnchanged(uploaded))
        assertEquals("新名字", store.find(uploaded.id)!!.name)
    }

    @Test fun editingDeletedDraftDoesNotResurrectIt() = runTest {
        val store = CaptureDraftStore(MemoryPreferences(), MemoryDao())
        val draft = store.create("正文", "笔记")
        assertTrue(store.clearIfUnchanged(draft))
        assertFalse(store.update(draft.id, "新名字", ""))
        assertEquals(0, store.count.first())
    }

    @Test fun metadataUpdatesAndListsDoNotReadWholeBodies() = runTest {
        val dao = MemoryDao(); val store = CaptureDraftStore(MemoryPreferences(), dao)
        val draft = store.create("正文".repeat(100_000), "笔记")
        repeat(30) { store.update(draft.id, "名字$it", "Inbox") }
        assertEquals(1, store.count.first())
        assertEquals(160, store.drafts.first().single().preview.length)
        assertEquals(0, dao.fullReads)
        assertEquals(draft.text, store.find(draft.id)!!.text)
    }

    @Test fun unchangedMetadataPreservesSnapshotForSafeDelete() = runTest {
        val store = CaptureDraftStore(MemoryPreferences(), MemoryDao())
        val draft = store.create("正文", "笔记", "Inbox")
        store.update(draft.id, draft.name, draft.folder)
        assertTrue(store.clearIfUnchanged(draft))
    }

    @Test fun legacySingleDraftMigratesOnceWithoutLosingContent() = runTest {
        val prefs = MemoryPreferences(); val dao = MemoryDao()
        prefs.edit().putString("text", "旧正文").putString("name", "旧名字").putString("folder", "Inbox").commit()
        val store = CaptureDraftStore(prefs, dao, StandardTestDispatcher(testScheduler))
        val id = store.drafts.first().single().id
        val migrated = store.find(id)!!
        assertEquals("旧正文", migrated.text)
        assertEquals("旧名字", migrated.name)
        assertEquals("Inbox", migrated.folder)
        assertFalse(prefs.contains("text"))
        assertEquals(migrated, CaptureDraftStore(prefs, dao).find(id))
    }

    @Test fun legacyMultipleDraftsKeepIdsTimestampsAndBodies() = runTest {
        val prefs = MemoryPreferences(); val dao = MemoryDao()
        val old = listOf(CaptureDraftStore.Draft("甲", "A", "", "id-a", 10L),
            CaptureDraftStore.Draft("乙", "B", "Inbox", "id-b", 20L))
        prefs.edit().putString("drafts", Json.encodeToString(old)).commit()
        val store = CaptureDraftStore(prefs, dao, StandardTestDispatcher(testScheduler))
        store.initialize()
        assertFalse(prefs.contains("drafts"))
        old.forEach { assertEquals(it, store.find(it.id)) }
    }

    @Test fun interruptedMigrationRetriesWithoutOverwritingLaterChanges() = runTest {
        val prefs = MemoryPreferences(); val dao = MemoryDao()
        val old = CaptureDraftStore.Draft("正文", "旧名字", "", "stable", 1L)
        prefs.edit().putString("drafts", Json.encodeToString(listOf(old))).commit()
        prefs.failCommit = true
        val store = CaptureDraftStore(prefs, dao, StandardTestDispatcher(testScheduler))
        try { store.initialize(); fail("Expected cleanup failure") } catch (_: IllegalStateException) { }
        assertTrue(prefs.contains("drafts"))
        dao.update(old.id, "更新后的名字", "Inbox", 2L)
        prefs.failCommit = false
        store.initialize()
        assertEquals("更新后的名字", store.find(old.id)!!.name)
        assertEquals(1, store.count.first())
    }

    @Test fun corruptLegacyDataRemainsAvailableForRecovery() = runTest {
        val prefs = MemoryPreferences()
        prefs.edit().putString("drafts", "invalid json").commit()
        val store = CaptureDraftStore(prefs, MemoryDao(), StandardTestDispatcher(testScheduler))
        try { store.initialize(); fail("Expected decode failure") } catch (_: kotlinx.serialization.SerializationException) { }
        assertEquals("invalid json", prefs.getString("drafts", null))
    }

    /** 只模拟草稿存储使用的字符串接口；不依赖 Android 运行时。 */
    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, String?>()
        var failCommit = false
        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? = values[key] ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, String?>()
            private var cleared = false
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { cleared = true }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                if (failCommit) return false
                if (cleared) values.clear()
                changes.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                return true
            }
            override fun putStringSet(key: String, values: Set<String>?) = error("Unused")
            override fun putInt(key: String, value: Int) = error("Unused")
            override fun putLong(key: String, value: Long) = error("Unused")
            override fun putFloat(key: String, value: Float) = error("Unused")
            override fun putBoolean(key: String, value: Boolean) = error("Unused")
        }
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = error("Unused")
        override fun getInt(key: String, defValue: Int): Int = error("Unused")
        override fun getLong(key: String, defValue: Long): Long = error("Unused")
        override fun getFloat(key: String, defValue: Float): Float = error("Unused")
        override fun getBoolean(key: String, defValue: Boolean): Boolean = error("Unused")
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    }
}
