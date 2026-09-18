package com.obsidiancompanion.feature.search

import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity

/** 正文命中：所属 Tree 条目 + 首个命中处摘要 + 总命中次数。 */
data class ContentMatch(
    val entry: RepoEntryEntity,
    val snippet: String,
    val count: Int,
)

private val WS_RUN = Regex("\\s+")

/** query 规范化：去首尾空白 + 连续空白折叠为单空格（与正文折叠同规则，对称匹配）。 */
private fun normalizeQuery(query: String): String = query.trim().replace(WS_RUN, " ")

/**
 * 正文搜索（§27 扩展）：大小写不敏感子串命中（与文件名同规则，CJK 天然支持），
 * 直接搜原始 markdown —— frontmatter / tag 一起可搜。按命中次数降序、path 升序。
 * snapshot 只含已缓存进 ContentCache 的条目，未缓存的笔记不参与（覆盖范围语义见 SearchViewModel）。
 *
 * 计数与摘要都在同一段空白折叠文本上计算（换行/连续空格视为单空格）：
 * 单一事实源，短语跨行也能命中；一篇只扫一遍。
 */
fun searchContents(snapshot: List<Pair<RepoEntryEntity, String>>, query: String): List<ContentMatch> {
    val q = normalizeQuery(query)
    if (q.isEmpty()) return emptyList()
    return snapshot.mapNotNull { (entry, content) ->
        val flat = content.replace(WS_RUN, " ")
        var first = -1
        var count = 0
        var idx = flat.indexOf(q, ignoreCase = true)
        while (idx >= 0) {
            if (first < 0) first = idx
            count++
            idx = flat.indexOf(q, idx + q.length, ignoreCase = true)
        }
        if (count == 0) null
        else ContentMatch(entry, snippetWindow(flat, first, q.length), count)
    }.sortedWith(compareByDescending<ContentMatch> { it.count }.thenBy { it.entry.path })
}

/** 摘要取窗（输入为已折叠文本）：[hitIndex, hitIndex+hitLength) 命中处前后各取窗，截断边加省略号。 */
fun snippetWindow(flat: String, hitIndex: Int, hitLength: Int, before: Int = 30, after: Int = 60): String {
    val start = maxOf(0, hitIndex - before)
    val end = minOf(flat.length, hitIndex + hitLength + after)
    val body = flat.substring(start, end).trim()
    return (if (start > 0) "…" else "") + body + (if (end < flat.length) "…" else "")
}
