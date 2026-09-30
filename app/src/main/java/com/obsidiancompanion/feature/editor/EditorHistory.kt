package com.obsidiancompanion.feature.editor

import androidx.compose.ui.text.input.TextFieldValue

/** 会话内的有界撤销历史：光标移动不入栈，输入法组合文本作为一次修改。 */
class EditorHistory(
    private val maxSteps: Int = 100,
    private val maxCharacters: Int = 2_000_000,
) {
    init {
        require(maxSteps > 0 && maxCharacters > 0)
    }

    private val past = ArrayDeque<TextFieldValue>()
    private val future = ArrayDeque<TextFieldValue>()
    private var compositionRecorded = false
    var current = TextFieldValue("")
        private set
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun reset(value: TextFieldValue) {
        past.clear()
        future.clear()
        compositionRecorded = false
        current = value.copy(composition = null)
    }

    fun record(value: TextFieldValue) {
        if (value.text != current.text) {
            if (!compositionRecorded) {
                past.addLast(current.copy(composition = null))
                trim(past)
            }
            future.clear()
        }
        compositionRecorded = value.composition != null && (compositionRecorded || value.text != current.text)
        current = value
    }

    fun undo(): TextFieldValue {
        compositionRecorded = false
        if (past.isNotEmpty()) {
            future.addLast(current.copy(composition = null))
            trim(future)
            current = past.removeLast()
        }
        return current
    }

    fun redo(): TextFieldValue {
        compositionRecorded = false
        if (future.isNotEmpty()) {
            past.addLast(current.copy(composition = null))
            trim(past)
            current = future.removeLast()
        }
        return current
    }

    private fun trim(stack: ArrayDeque<TextFieldValue>) {
        var characters = stack.sumOf { it.text.length.toLong() }
        while (stack.size > maxSteps || (stack.size > 1 && characters > maxCharacters)) {
            characters -= stack.removeFirst().text.length
        }
    }
}
