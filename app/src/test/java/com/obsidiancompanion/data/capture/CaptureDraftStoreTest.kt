package com.obsidiancompanion.data.capture

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDraftStoreTest {
    @Test
    fun savedDraft_restoresTextNameAndFolderFromPreferences() {
        val prefs = MemoryPreferences()
        CaptureDraftStore(prefs).save("分享正文", "修改后的名字", "收集/网页")
        assertEquals(
            CaptureDraftStore.Draft("分享正文", "修改后的名字", "收集/网页"),
            CaptureDraftStore(prefs).draft.value,
        )
    }

    @Test
    fun successfulUpload_clearsMatchingDraftAndPersistedCopy() {
        val prefs = MemoryPreferences()
        val store = CaptureDraftStore(prefs)
        store.save("分享正文", "笔记", "Inbox")
        assertTrue(store.clearIfUnchanged(store.draft.value!!))
        assertNull(store.draft.value)
        assertNull(CaptureDraftStore(prefs).draft.value)
    }

    @Test
    fun completingOlderUpload_doesNotDeleteNewerDraft() {
        val prefs = MemoryPreferences()
        val store = CaptureDraftStore(prefs)
        store.save("旧内容", "旧笔记", "")
        val uploaded = store.draft.value!!
        store.save("新内容", "新笔记", "Inbox")
        assertFalse(store.clearIfUnchanged(uploaded))
        assertEquals(
            CaptureDraftStore.Draft("新内容", "新笔记", "Inbox"),
            CaptureDraftStore(prefs).draft.value,
        )
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
