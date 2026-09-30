package com.obsidiancompanion.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class EditorHistoryTest {
    @Test fun undoAndRedo_restoreTextAndSelection() {
        val history = EditorHistory()
        val original = TextFieldValue("正文", TextRange(1))
        val edited = TextFieldValue("**正文**", TextRange(2, 4))
        history.reset(original)
        history.record(edited)
        assertEquals(original, history.undo())
        assertEquals(edited, history.redo())
    }

    @Test fun cursorMoves_doNotCreateStepsOrClearRedo() {
        val history = EditorHistory()
        history.reset(TextFieldValue("abc"))
        history.record(TextFieldValue("abc", TextRange(2)))
        assertFalse(history.canUndo)
        history.record(TextFieldValue("abcd"))
        history.undo()
        history.record(TextFieldValue("abc", TextRange(1)))
        assertTrue(history.canRedo)
        assertEquals("abcd", history.redo().text)
    }

    @Test fun newEditAfterUndo_discardsRedoBranch() {
        val history = EditorHistory()
        history.record(TextFieldValue("a"))
        history.record(TextFieldValue("ab"))
        history.undo()
        history.record(TextFieldValue("ac"))
        assertFalse(history.canRedo)
        assertEquals("a", history.undo().text)
    }

    @Test fun imeComposition_isOneUndoStep() {
        val history = EditorHistory()
        history.reset(TextFieldValue(""))
        history.record(TextFieldValue("n", composition = TextRange(0, 1)))
        history.record(TextFieldValue("ni", composition = TextRange(0, 2)))
        history.record(TextFieldValue("你", TextRange(1)))
        assertEquals("", history.undo().text)
        assertFalse(history.canUndo)
        assertEquals("你", history.redo().text)
        assertNull(history.current.composition)
    }

    @Test fun compositionStartingWithoutTextChange_stillRecordsOriginal() {
        val history = EditorHistory()
        history.reset(TextFieldValue("a"))
        history.record(TextFieldValue("a", composition = TextRange(0, 1)))
        history.record(TextFieldValue("ab", composition = TextRange(0, 2)))
        history.record(TextFieldValue("abc"))
        assertEquals("a", history.undo().text)
        assertFalse(history.canUndo)
    }

    @Test fun reset_clearsHistoryWhenLoadingAnotherDocument() {
        val history = EditorHistory()
        history.record(TextFieldValue("old"))
        history.reset(TextFieldValue("new"))
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals("new", history.undo().text)
    }

    @Test fun history_isBoundedByStepsAndTextSize() {
        val bySteps = EditorHistory(maxSteps = 2)
        listOf("a", "ab", "abc").forEach { bySteps.record(TextFieldValue(it)) }
        assertEquals("ab", bySteps.undo().text)
        assertEquals("a", bySteps.undo().text)
        assertFalse(bySteps.canUndo)
        val bySize = EditorHistory(maxCharacters = 5)
        bySize.reset(TextFieldValue("1234"))
        bySize.record(TextFieldValue("12345"))
        bySize.record(TextFieldValue("123456"))
        assertEquals("12345", bySize.undo().text)
        assertFalse(bySize.canUndo)
    }
}
