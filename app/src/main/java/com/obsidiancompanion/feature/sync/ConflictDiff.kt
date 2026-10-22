package com.obsidiancompanion.feature.sync

enum class ConflictLineKind { UNCHANGED, REMOTE_ONLY, MINE_ONLY }
data class ConflictDiffLine(val text: String, val kind: ConflictLineKind)
data class ConflictDiff(val lines: List<ConflictDiffLine>, val simplified: Boolean = false)

/** 按行对比 GitHub 与手机正文；较大的变化范围整体标记，避免平方级内存与计算量。 */
internal fun conflictLineDiff(remote: String, mine: String): ConflictDiff {
    val before = if (remote.isEmpty()) emptyList() else remote.lines()
    val after = if (mine.isEmpty()) emptyList() else mine.lines()
    var prefix = 0
    while (prefix < minOf(before.size, after.size) && before[prefix] == after[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(before.size, after.size) - prefix &&
        before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
    val old = before.subList(prefix, before.size - suffix)
    val new = after.subList(prefix, after.size - suffix)
    val lines = mutableListOf<ConflictDiffLine>()
    before.take(prefix).forEach { lines += ConflictDiffLine(it, ConflictLineKind.UNCHANGED) }
    val simplified = old.size.toLong() * new.size > 250_000L
    if (simplified || old.isEmpty() || new.isEmpty()) {
        old.forEach { lines += ConflictDiffLine(it, ConflictLineKind.REMOTE_ONLY) }
        new.forEach { lines += ConflictDiffLine(it, ConflictLineKind.MINE_ONLY) }
    } else {
        val lengths = Array(old.size + 1) { IntArray(new.size + 1) }
        for (i in old.indices.reversed()) for (j in new.indices.reversed()) {
            lengths[i][j] = if (old[i] == new[j]) lengths[i + 1][j + 1] + 1
                else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < old.size || j < new.size) {
            when {
                i < old.size && j < new.size && old[i] == new[j] -> {
                    lines += ConflictDiffLine(old[i++], ConflictLineKind.UNCHANGED)
                    j++
                }
                i < old.size && (j == new.size || lengths[i + 1][j] >= lengths[i][j + 1]) ->
                    lines += ConflictDiffLine(old[i++], ConflictLineKind.REMOTE_ONLY)
                else -> lines += ConflictDiffLine(new[j++], ConflictLineKind.MINE_ONLY)
            }
        }
    }
    before.takeLast(suffix).forEach { lines += ConflictDiffLine(it, ConflictLineKind.UNCHANGED) }
    return ConflictDiff(lines, simplified)
}
