package com.obsidiancompanion.data.capture

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 分享收集本机草稿（单槽）：分享收到即落盘（含笔记名 / 目录修改），
 * 离线 / 退出页面 / 杀进程都不丢；只有上传成功才清除（失败保留以便重试）。
 * 新分享替换旧草稿前由界面要求确认。一条草稿用 SharedPreferences 足够，不进 Room。
 */
class CaptureDraftStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("capture_draft", Context.MODE_PRIVATE))

    data class Draft(val text: String, val name: String, val folder: String)

    private val state = MutableStateFlow(read())
    val draft: StateFlow<Draft?> = state.asStateFlow()

    fun save(text: String, name: String, folder: String) {
        prefs.edit()
            .putString(KEY_TEXT, text)
            .putString(KEY_NAME, name)
            .putString(KEY_FOLDER, folder)
            .apply()
        state.value = Draft(text, name, folder)
    }

    /** 仅上传成功后调用（失败保留草稿，可重试且不会重复上传）。 */
    fun clearIfUnchanged(expected: Draft): Boolean {
        if (state.value != expected) return false
        prefs.edit().clear().apply()
        state.value = null
        return true
    }

    private fun read(): Draft? {
        val text = prefs.getString(KEY_TEXT, null) ?: return null
        return Draft(
            text = text,
            name = prefs.getString(KEY_NAME, "").orEmpty(),
            folder = prefs.getString(KEY_FOLDER, "").orEmpty(),
        )
    }

    private companion object {
        const val KEY_TEXT = "text"
        const val KEY_NAME = "name"
        const val KEY_FOLDER = "folder"
    }
}
