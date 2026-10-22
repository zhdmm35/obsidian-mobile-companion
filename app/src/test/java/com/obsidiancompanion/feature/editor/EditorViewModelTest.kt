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
import com.obsidiancompanion.model.markdown.MdBlock
import com.obsidiancompanion.data.markdown.MarkdownParser
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

    @Test fun taskTogglesUseHistoryLocalDraftAndVersionCheckedSave() = runTest {
        val editor = editor()
        runCurrent()
        val source = "---\r\ntitle: test\r\n---\r\n- [ ] task\r\n"
        editor.onValueChange(TextFieldValue(source))
        val doc = MarkdownParser.parse(source)
        val task = (doc.blocks.single() as MdBlock.TaskList).items.single()
        assertTrue(editor.toggleTask(source, doc.frontmatter, task))
        val updated = source.replace("[ ]", "[x]")
        assertEquals(updated, editor.value.text)
        editor.undo()
        assertEquals(source, editor.value.text)
        editor.redo()
        assertEquals(updated, editor.value.text)
        editor.onBackground()
        runCurrent()
        assertEquals(updated, notes.pending!!.content)
        editor.save({}, {}, {})
        runCurrent()
        assertEquals("indexed", notes.savedBase)
        assertEquals(updated, notes.savedText)
    }

    @Test fun staleTaskPreviewCannotChangeCurrentTextOrToggleTwice() = runTest {
        val editor = editor()
        runCurrent()
        val source = "- [ ] task"
        editor.onValueChange(TextFieldValue(source))
        val task = (MarkdownParser.parse(source).blocks.single() as MdBlock.TaskList).items.single()
        assertTrue(editor.toggleTask(source, null, task))
        assertFalse(editor.toggleTask(source, null, task))
        editor.onValueChange(TextFieldValue("- [ ] other"))
        assertFalse(editor.toggleTask(source, null, task))
        assertEquals("- [ ] other", editor.value.text)
    }

    @Test fun taskModeIsInitialOnlyAndSavingBlocksToggles() = runTest {
        val editor = EditorViewModel(notes, { true }, {})
        store.put("editor", editor)
        editor.load("A.md", taskMode = true)
        runCurrent()
        assertTrue(editor.preview)
        editor.preview = false
        editor.load("A.md", taskMode = true)
        assertFalse(editor.preview)
        val source = "- [ ] task"
        editor.onValueChange(TextFieldValue(source))
        val task = (MarkdownParser.parse(source).blocks.single() as MdBlock.TaskList).items.single()
        notes.gate = CompletableDeferred()
        editor.save({}, {}, {})
        runCurrent()
        assertFalse(editor.toggleTask(source, null, task))
        notes.gate!!.complete(Unit)
        runCurrent()
    }

    @Test fun backgroundImmediatelyStagesTextWithoutWaitingForDebounce() = runTest {
        val editor = editor()
        runCurrent()
        editor.onValueChange(TextFieldValue("just typed"))
        editor.onBackground()
        runCurrent()
        assertEquals("just typed", notes.pending!!.content)
        assertEquals("indexed", notes.pending!!.baseSha)
        assertTrue(editor.draftStaged)
    }

    @Test fun backgroundUsesTheRepositoryOfTheLoadedNoteAfterSettingsChange() = runTest {
        val editor = editor()
        runCurrent()
        notes.currentRepo = "switched/repo"
        editor.onValueChange(TextFieldValue("original repository edit"))
        editor.onBackground()
        runCurrent()
        assertEquals("o/r", notes.pending!!.repoId)
    }

    @Test fun backgroundAfterUndoToOriginalClearsThePreviousDraftImmediately() = runTest {
        val editor = editor()
        runCurrent()
        editor.onValueChange(TextFieldValue("temporary edit"))
        editor.onBackground()
        runCurrent()
        assertEquals("temporary edit", notes.pending!!.content)
        editor.undo()
        assertFalse(editor.isDirty)
        assertFalse(editor.canUndo)
        assertTrue(editor.canRedo)
        editor.onBackground()
        runCurrent()
        assertNull(notes.pending)
    }

    @Test fun backgroundWriteSurvivesEditorDestruction() = runTest {
        val editor = EditorViewModel(notes, { true }, {}, backgroundScope)
        store.put("editor", editor)
        editor.load("A.md")
        runCurrent()
        notes.stageGate = CompletableDeferred()
        editor.onValueChange(TextFieldValue("keep after closing"))
        editor.onBackground()
        runCurrent()
        store.clear()
        notes.stageGate!!.complete(Unit)
        runCurrent()
        assertEquals("keep after closing", notes.pending!!.content)
    }

    @Test fun saveWaitsForBackgroundWriteAndDoesNotResurrectDraft() = runTest {
        val editor = editor()
        runCurrent()
        notes.stageGate = CompletableDeferred()
        editor.onValueChange(TextFieldValue("edit"))
        editor.onBackground()
        runCurrent()
        editor.save({}, {}, {})
        runCurrent()
        assertEquals(0, notes.saveCalls)
        notes.stageGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, notes.saveCalls)
        assertNull(notes.pending)
    }

    @Test fun backgroundDoesNotDeleteUnrestoredDraftOrFlushWhileSaving() = runTest {
        notes.pending = PendingEditEntity("o/r", "A.md", "oldbase", "old draft", 1L)
        val editor = editor()
        runCurrent()
        editor.keepDraft()
        editor.onBackground()
        runCurrent()
        assertEquals("old draft", notes.pending!!.content)
        editor.onValueChange(TextFieldValue("new edit"))
        notes.gate = CompletableDeferred()
        editor.save({}, {}, {})
        runCurrent()
        editor.onBackground()
        notes.gate!!.complete(Unit)
        runCurrent()
        assertNull(notes.pending)
    }

    @Test fun backgroundFailureKeepsTextAndReportsLocalFailure() = runTest {
        val editor = editor()
        runCurrent()
        notes.stageException = IOException("disk full")
        editor.onValueChange(TextFieldValue("copy me"))
        editor.onBackground()
        runCurrent()
        assertEquals("copy me", editor.value.text)
        assertFalse(editor.draftStaged)
        assertFalse(editor.saveFailure!!.locallySaved)
    }

    @Test fun stoppingAfterDiscardDoesNotRecreateTheDiscardedDraft() = runTest {
        val editor = editor()
        runCurrent()
        editor.onValueChange(TextFieldValue("discard me"))
        editor.discardAndLeave {}
        runCurrent()
        editor.onBackground()
        runCurrent()
        assertNull(notes.pending)
    }

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
        var stageGate: CompletableDeferred<Unit>? = null
        var stageException: Throwable? = null
        var currentRepo = "o/r"
        override suspend fun openNote(path: String): NoteOpenResult = NoteOpenResult.Content(
            path, "base", MdDocument(null, emptyList()), true,
            RepoEntryEntity("o/r", path, path, null, EntryKind.MARKDOWN, "indexed", 4L, null), 1L,
        )
        override suspend fun getPendingEdit(path: String) = pending
        override suspend fun getCachedContent(sha: String) = "base"
        override suspend fun stagePendingEdit(path: String, baseSha: String, content: String, repoId: String?) {
            stageGate?.await()
            stageException?.let { throw it }
            pending = PendingEditEntity(repoId ?: currentRepo, path, baseSha, content, 1L)
        }
        override suspend fun clearPendingEdit(path: String, repoId: String?) { pending = null }
        override suspend fun saveNote(path: String, baseSha: String, content: String, expectedRepoId: String?): NoteSaveResult {
            saveCalls++
            saveException?.let { throw it }
            stagePendingEdit(path, baseSha, content, expectedRepoId)
            savedBase = baseSha
            savedText = content
            gate?.await()
            if (result is NoteSaveResult.Saved) pending = null
            return result
        }
    }
}
