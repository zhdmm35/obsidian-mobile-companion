package com.obsidiancompanion.data.repository

import com.obsidiancompanion.data.github.RemoteTree
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity

/**
 * Tree Diff（§21/§72）：新旧 Tree 按 path + blobSha 比较，输出 Added / Changed / Deleted / Unchanged
 * 与可直接整体替换 Room 的实体列表。纯函数，可单测。
 */
object TreeDiff {

    data class Result(
        val entries: List<RepoEntryEntity>,
        val addedPaths: List<String>,
        val changedPaths: List<String>,
        val deletedPaths: List<String>,
        val unchangedCount: Int,
        val firstLoad: Boolean,
    ) {
        val hasChanges: Boolean get() = addedPaths.isNotEmpty() || changedPaths.isNotEmpty() || deletedPaths.isNotEmpty()

        /**
         * 增量入库需要 upsert 的行：firstLoad 时全部（此时 addedPaths 按定义为空），
         * 否则只有 Added + Changed（Unchanged 行各字段均由 path+sha+旧 observedChangedAt 派生，与库中一致，可跳过）。
         */
        fun upsertEntries(): List<RepoEntryEntity> {
            if (firstLoad) return entries
            if (addedPaths.isEmpty() && changedPaths.isEmpty()) return emptyList()
            val upsertPaths = (addedPaths + changedPaths).toSet()
            return entries.filter { it.path in upsertPaths }
        }
    }

    fun compute(
        repoId: String,
        old: List<RepoEntryEntity>,
        remote: RemoteTree,
        now: Long,
    ): Result {
        val firstLoad = old.isEmpty()
        val oldByPath = old.associateBy { it.path }

        val entries = mutableListOf<RepoEntryEntity>()
        val added = mutableListOf<String>()
        val changed = mutableListOf<String>()
        var unchanged = 0

        remote.entries.forEach { e ->
            // §26：隐藏路径（.git/.obsidian/.trash/.* 等）在入库时过滤 —— 只是 UI 过滤，绝不调任何删除 API
            if (e.path.split('/').any { it.startsWith(".") }) return@forEach

            val previous = oldByPath[e.path]
            val observed = when {
                previous == null -> if (firstLoad) null else now       // §23：首次加载全部 null
                previous.blobSha != e.sha -> now                        // §22：检测到远端 SHA 变化
                else -> previous.observedChangedAt                      // Unchanged 不更新时间
            }
            if (previous != null && previous.blobSha == e.sha) unchanged++
            if (previous == null && !firstLoad) added += e.path
            if (previous != null && previous.blobSha != e.sha) changed += e.path

            entries += RepoEntryEntity(
                repoId = repoId,
                path = e.path,
                name = e.path.substringAfterLast('/'),
                parentPath = e.path.substringBeforeLastOrNull('/'),
                kind = classify(e.path, e.isDirectory),
                blobSha = e.sha,
                size = e.size,
                observedChangedAt = observed,
            )
        }

        val newPaths = entries.map { it.path }.toSet()
        val deleted = oldByPath.keys.filter { it !in newPaths }

        return Result(
            entries = entries,
            addedPaths = added,
            changedPaths = changed,
            deletedPaths = deleted,
            unchangedCount = unchanged,
            firstLoad = firstLoad,
        )
    }

    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif")

    fun classify(path: String, isDirectory: Boolean): EntryKind = when {
        isDirectory -> EntryKind.DIRECTORY
        else -> when (path.substringAfterLast('.', "").lowercase()) {
            "md", "markdown" -> EntryKind.MARKDOWN
            "pdf" -> EntryKind.PDF
            "canvas" -> EntryKind.CANVAS
            in imageExtensions -> EntryKind.IMAGE
            else -> EntryKind.OTHER
        }
    }

    private fun String.substringBeforeLastOrNull(delimiter: Char): String? {
        val idx = lastIndexOf(delimiter)
        return if (idx <= 0) null else substring(0, idx)
    }
}
