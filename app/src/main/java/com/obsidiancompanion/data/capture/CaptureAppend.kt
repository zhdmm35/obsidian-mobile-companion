package com.obsidiancompanion.data.capture

import java.security.MessageDigest

/** 每条分享一个稳定标记：响应丢失后重试可识别已上传的内容，相同正文的两条分享仍各自保留。 */
object CaptureAppend {
    private val markerPattern = Regex("<!-- inkstone-capture:[0-9a-f]{64} -->")

    fun marker(draftId: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(draftId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "<!-- inkstone-capture:$hash -->"
    }

    fun isMarker(text: String): Boolean = markerPattern.matches(text)

    fun alreadyAppended(existing: String, draftId: String): Boolean {
        val expected = marker(draftId)
        return existing.lineSequence().any { it == expected }
    }

    fun append(existing: String, draftId: String, text: String): String {
        if (alreadyAppended(existing, draftId)) return existing
        // 保留已有字节，包括 CRLF 和末尾空白；空行将新内容与原文块分开。
        val separator = if (existing.isEmpty()) "" else "\n\n---\n\n"
        return existing + separator + text + "\n\n" + marker(draftId) + "\n"
    }
}
