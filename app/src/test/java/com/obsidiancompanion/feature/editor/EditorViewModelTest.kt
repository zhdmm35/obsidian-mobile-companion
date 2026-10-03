package com.obsidiancompanion.feature.editor

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelStore
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.PendingEditEntity
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.data.repository.EditorNoteStore
import com.obsidiancompanion.data.repository.NoteOpenResult
import com.obsidiancompanion.data.repository.NoteSaveResult
import com.obsidiancompanion.model.markdown.MdDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private val store = ViewModelStore()
    private val notes = FakeNotes()
    private var refreshed = 0

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private fun editor(online: Boolean = true): EditorViewModel =
        EditorViewModel(notes, { online }, { refreshed++ }).also { store.put("editor", it); it.load("A.md") }

    @Test fun successfulSave_usesFrozenBaseAndRefreshesOnce() = runTest {
        val editor = editor()
        runCurrent()
        editor.onValueChange(TextFieldValue("edited"))
        var left = false
        editor.save({ left = true }, { fail("unexpected conflict") }, {})
        runCurrent()
        assertEquals("indexed", notes.savedBase)
        assertEquals("edited", notes.savedText)
        assertTrue(left)
        assertFalse(editor.isDirty)
        assertEquals(1, refreshed)
    }

    @Test fun restoredDraft_usesOriginalDraftBaseEvenWithNewIndex() = runTest {
        notes.pending = PendingEditEntity("o/r", "A.md", "oldbase", "draft", 1L)
        val editor = editor()
        runCurrent()
        editor.restoreDraft()
        editor.save({}, {}, {})
        runCurrent()
        assertEquals("oldbase", notes.savedBase)
        assertEquals("draft", notes.savedText)
    }

    @Test fun offlineSave_stagesLatestTextAndAllowsRetry() = runTest {
        val editor = editor(online = false)
        runCurrent()
        editor.onValueChange(TextFieldValue("offline edit"))
        editor.save({ fail("must stay") }, {}, {})
        runCurrent()
        assertEquals("offline edit", notes.pending!!.content)
        assertEquals(0, notes.saveCalls)
        assertTrue(editor.saveFailure!!.locallySaved)
        assertEquals(EditorViewModel.SaveState.EDITING, editor.saveState)
    }

    @Test fun conflict_keepsTextAndOpensConflictWithoutRefresh() = runTest {
        notes.result = NoteSaveResult.Conflict("A.md")
        val editor = editor()
        runCurrent()
        editor.onValueChange(TextFieldValue("my edit"))
        var conflict = false
        editor.save({ fail("must stay") }, { conflict = true }, {})
        runCurrent()
        assertTrue(conflict)
        assertEquals("my edit", editor.value.text)
        assertEquals("my edit", notes.pending!!.content)
        assertEquals(0, refreshed)
    }

    @Test fun repeatedSaveWhileUploading_doesNotStartSecondWrite() = runTest {
        notes.gate = CompletableDeferred()
        val editor = editor()
        runCurrent()
        editor.onValueChange(TextFieldValue("edit"))
        editor.save({}, {}, {})
        runCurrent()
        editor.save({}, {}, {})
        runCurrent()
        assertEquals(1, notes.saveCalls)
        editor.onValueChange(TextFieldValue("late input"))
        assertEquals("edit", editor.value.text)
        notes.gate!!.complete(Unit)
        runCurrent()
    }

    @Test fun keepAndLeave_waitsForLocalDraftBeforeNavigation() = runTest {
        val editor = editor()
        runCurrent()
        editor.onValueChange(TextFieldValue("keep me"))
        editor.keepAndLeave { assertEquals("keep me", notes.pending!!.content) }
        runCurrent()
        assertEquals("keep me", notes.pending!!.content)
    }

    @Test fun localWriteFailure_keepsInputAndRestoresEditingState() = runTest {
        notes.saveException = IOException("disk full")
        val editor = editor()
        runCurrent()
        editor.onValueChange(TextFieldValue("important text"))
        editor.save({ fail("must stay") }, {}, {})
        runCurrent()
        assertEquals("important text", editor.value.text)
        assertEquals(EditorViewModel.SaveState.EDITING, editor.saveState)
        assertFalse(editor.saveFailure!!.locallySaved)
    }

    @Test fun cancelledSave_restoresStateWithoutReportingError() = runTest {
        notes.saveException = CancellationException("cancelled")
        val editor = editor()
        runCurrent()
        editor.onValueChange(TextFieldValue("edit"))
        editor.save({}, {}, {})
        runCurrent()
        assertEquals(EditorViewModel.SaveState.EDITING, editor.saveState)
        assertNull(editor.saveFailure)
    }

    private class FakeNotes : EditorNoteStore {
        var pending: PendingEditEntity? = null
        var result: NoteSaveResult = NoteSaveResult.Saved("A.md", "new", "commit")
        var gate: CompletableDeferred<Unit>? = null
        var saveException: Throwable? = null
        var saveCalls = 0
        var savedBase: String? = null
        var savedText: String? = null
        override suspend fun openNote(path: String): NoteOpenResult = NoteOpenResult.Content(
            path, "base", MdDocument(null, emptyList()), true,
            RepoEntryEntity("o/r", path, path, null, EntryKind.MARKDOWN, "indexed", 4L, null), 1L,
        )
        override suspend fun getPendingEdit(path: String) = pending
        override suspend fun getCachedContent(sha: String) = "base"
        override suspend fun stagePendingEdit(path: String, baseSha: String, content: String) {
            pending = PendingEditEntity("o/r", path, baseSha, content, 1L)
        }
        override suspend fun clearPendingEdit(path: String) { pending = null }
        override suspend fun saveNote(path: String, baseSha: String, content: String): NoteSaveResult {
            saveCalls++
            saveException?.let { throw it }
            stagePendingEdit(path, baseSha, content)
            savedBase = baseSha
            savedText = content
            gate?.await()
            if (result is NoteSaveResult.Saved) pending = null
            return result
        }
    }
}
