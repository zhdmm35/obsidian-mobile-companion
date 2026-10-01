package com.obsidiancompanion.feature.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** 与搜索相同的大小写不敏感、非重叠字面匹配；保留原文字和索引。 */
internal fun highlightSearchMatches(text: String, query: String, style: SpanStyle): AnnotatedString {
    val keyword = query.trim()
    return buildAnnotatedString {
        append(text)
        if (keyword.isEmpty()) return@buildAnnotatedString
        var start = text.indexOf(keyword, ignoreCase = true)
        while (start >= 0) {
            val end = start + keyword.length
            addStyle(style, start, end)
            start = text.indexOf(keyword, startIndex = end, ignoreCase = true)
        }
    }
}
