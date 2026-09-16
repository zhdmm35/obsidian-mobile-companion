package com.obsidiancompanion.feature.editor

/**
 * 编辑器工具条的文本变换（纯函数，无 Android 依赖 → JVM 可测）。
 * Phase 5 §31-§35：H / Bold / List / Task / WikiLink 的插入与光标语义。
 */
object EditorTextOps {

    /** [text] 变换结果 + 新光标位置（始终折叠为单光标，不保留选区）。 */
    data class EditResult(val text: String, val cursor: Int)

    /**
     * 选区包裹（§32/§35）：有选区 `abc` → `**abc**`，光标到包裹末尾；
     * 无选区插入 `****`，光标落在成对符号中间。
     */
    fun wrap(text: String, selStart: Int, selEnd: Int, before: String, after: String): EditResult {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)
        val wrapped = before + text.substring(start, end) + after
        val newText = text.substring(0, start) + wrapped + text.substring(end)
        val cursor = if (start == end) start + before.length else start + wrapped.length
        return EditResult(newText, cursor)
    }

    /**
     * 行首前缀（§31/§33/§34）：当前行加 `## ` / `- ` / `- [ ] `，光标随插入平移；
     * 当前行已有相同前缀时改为移除（toggle），光标随删除左移但不越过行首。
     */
    fun linePrefix(text: String, cursor: Int, prefix: String): EditResult {
        val c = cursor.coerceIn(0, text.length)
        val lineStart = if (c == 0) {
            0
        } else {
            val nl = text.lastIndexOf('\n', c - 1)
            if (nl < 0) 0 else nl + 1
        }
        return if (text.startsWith(prefix, lineStart)) {
            EditResult(
                text.removeRange(lineStart, lineStart + prefix.length),
                (c - prefix.length).coerceAtLeast(lineStart),
            )
        } else {
            val newText = text.substring(0, lineStart) + prefix + text.substring(lineStart)
            EditResult(newText, c + prefix.length)
        }
    }
}
