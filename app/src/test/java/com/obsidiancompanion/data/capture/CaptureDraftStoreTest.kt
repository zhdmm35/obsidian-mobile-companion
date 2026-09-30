package com.obsidiancompanion.data.capture

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDraftStoreTest {
    @Test
    fun savedDraft_restoresTextNameFolderAndStableIdentity() {
        val prefs = MemoryPreferences()
        val saved = CaptureDraftStore(prefs).create("分享正文", "修改后的名字", "收集/网页")
        assertEquals(listOf(saved), CaptureDraftStore(prefs).drafts.value)
    }

    @Test
    fun multipleShares_doNotOverwriteEvenWithIdenticalText() {
        val prefs = MemoryPreferences()
        val store = CaptureDraftStore(prefs)
        val first = store.create("正文", "笔记", "")
        val second = store.create("正文", "笔记", "Inbox")
        assertFalse(first.id == second.id)
        assertEquals(listOf(second, first), CaptureDraftStore(prefs).drafts.value)
    }

    @Test
    fun successfulUpload_onlyClearsItsOwnUnchangedDraft() {
        val prefs = MemoryPreferences()
        val store = CaptureDraftStore(prefs)
        val uploaded = store.create("正文", "笔记", "")
        val other = store.create("其他", "其他笔记", "Inbox")
        assertTrue(store.clearIfUnchanged(uploaded))
        assertEquals(listOf(other), CaptureDraftStore(prefs).drafts.value)
    }

    @Test
    fun completingOlderUpload_doesNotDeleteModifiedDraft() {
        val prefs = MemoryPreferences()
        val store = CaptureDraftStore(prefs)
        val uploaded = store.create("正文", "旧名字", "")
        val changed = store.update(uploaded.id, "新名字", "Inbox")
        assertFalse(store.clearIfUnchanged(uploaded))
        assertEquals(changed, CaptureDraftStore(prefs).find(uploaded.id))
    }

    @Test
    fun editingDeletedDraft_doesNotResurrectIt() {
        val prefs = MemoryPreferences()
        val store = CaptureDraftStore(prefs)
        val draft = store.create("正文", "笔记", "")
        assertTrue(store.clearIfUnchanged(draft))
        assertNull(store.update(draft.id, "新名字", ""))
        assertTrue(CaptureDraftStore(prefs).drafts.value.isEmpty())
    }

    @Test
    fun legacySingleDraft_migratesOnceWithoutLosingContent() {
        val prefs = MemoryPreferences()
        prefs.edit().putString("text", "旧分享正文").putString("name", "旧名字").putString("folder", "Inbox").commit()
        val migrated = CaptureDraftStore(prefs).drafts.value.single()
        assertEquals("旧分享正文", migrated.text)
        assertEquals("旧名字", migrated.name)
        assertEquals("Inbox", migrated.folder)
        assertFalse(prefs.contains("text"))
        assertEquals(migrated, CaptureDraftStore(prefs).drafts.value.single())
    }

    /** 只模拟草稿存储使用的字符串接口；不依赖 Android 运行时。 */
    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, String?>()
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
