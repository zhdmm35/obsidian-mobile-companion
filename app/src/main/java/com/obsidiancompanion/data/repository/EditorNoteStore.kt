package com.obsidiancompanion.data.repository

import com.obsidiancompanion.data.metadata.entities.PendingEditEntity

/** 编辑会话所需的读写能力；实现继续由 NoteRepository 提供。 */
interface EditorNoteStore {
    suspend fun openNote(path: String): NoteOpenResult
    suspend fun getPendingEdit(path: String): PendingEditEntity?
    suspend fun getCachedContent(sha: String): String?
    suspend fun stagePendingEdit(path: String, baseSha: String, content: String)
    suspend fun clearPendingEdit(path: String)
    suspend fun saveNote(path: String, baseSha: String, content: String): NoteSaveResult
}
