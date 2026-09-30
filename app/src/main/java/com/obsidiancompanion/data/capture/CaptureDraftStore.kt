package com.obsidiancompanion.data.capture

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 每次分享独立保存；旧版单条草稿自动迁移，只有明确丢弃或上传成功才删除。 */
class CaptureDraftStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("capture_draft", Context.MODE_PRIVATE))

    @Serializable
    data class Draft(
        val text: String,
        val name: String,
        val folder: String,
        val id: String = UUID.randomUUID().toString(),
        val updatedAt: Long = System.currentTimeMillis(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = MutableStateFlow(read())
    val drafts: StateFlow<List<Draft>> = state.asStateFlow()

    @Synchronized
    fun create(text: String, name: String, folder: String = ""): Draft {
        val draft = Draft(text, name, folder)
        persist(listOf(draft) + state.value)
        return draft
    }

    @Synchronized
    fun update(id: String, name: String, folder: String): Draft? {
        val old = state.value.find { it.id == id } ?: return null
        if (old.name == name && old.folder == folder) return old
        val changed = old.copy(name = name, folder = folder, updatedAt = System.currentTimeMillis())
        persist(state.value.map { if (it.id == id) changed else it }.sortedByDescending { it.updatedAt })
        return changed
    }

    @Synchronized
    fun find(id: String): Draft? = state.value.find { it.id == id }

    /** 比较完整快照：迟到的上传结果/丢弃确认不能删除后来修改的草稿。 */
    @Synchronized
    fun clearIfUnchanged(expected: Draft): Boolean {
        if (find(expected.id) != expected) return false
        persist(state.value.filterNot { it.id == expected.id })
        return true
    }

    private fun persist(drafts: List<Draft>) {
        check(prefs.edit().putString("drafts", json.encodeToString(drafts))
            .remove("text").remove("name").remove("folder").commit()) { "Cannot persist capture drafts" }
        state.value = drafts
    }

    private fun read(): List<Draft> {
        val encoded = prefs.getString("drafts", null)
        if (encoded != null) return json.decodeFromString<List<Draft>>(encoded)
            .sortedByDescending { it.updatedAt }
        val text = prefs.getString("text", null) ?: return emptyList()
        val migrated = listOf(Draft(text, prefs.getString("name", "").orEmpty(), prefs.getString("folder", "").orEmpty()))
        check(prefs.edit().putString("drafts", json.encodeToString(migrated))
            .remove("text").remove("name").remove("folder").commit()) { "Cannot migrate capture draft" }
        return migrated
    }
}
