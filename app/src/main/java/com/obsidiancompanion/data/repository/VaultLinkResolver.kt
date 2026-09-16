package com.obsidiancompanion.data.repository

import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity

/**
 * WikiLink / 嵌入目标解析（Phase 4 §11-§13/§26/§57）。
 *
 * 数据源 = Room Tree Cache（Phase 3 已缓存），绝不为解析链接再请求 GitHub；
 * 每次解析直接读 Room，Tree 刷新后自然拿到最新结果（§58：不做永不更新的内存 Map）。
 *
 * 解析顺序（§12）：
 *   1. 当前笔记同目录精确匹配
 *   2. Vault 全局 basename 精确匹配（唯一才命中）
 *   3. 显式相对 path 匹配（含省略 .md）
 *   4. 无结果 → NotFound（Dead link）；同目录无法消歧的多重命中 → Ambiguous
 *
 * Missing 与 Existing-but-uncached 是两种语义（§57）：本解析器只回答 Tree 中
 * 「有没有」，内容是否已缓存由 Reader / ImageRepository 各自判断。
 */
sealed interface LinkResolution {
    /** `[[#Heading]]`：同笔记锚点，不 push 新 Reader（§19）。 */
    data class SameNote(val heading: String?) : LinkResolution

    data class Note(val path: String, val heading: String?) : LinkResolution

    data class Ambiguous(val candidates: List<String>) : LinkResolution

    data object NotFound : LinkResolution
}

sealed interface ImageResolution {
    data class Found(val path: String, val blobSha: String, val size: Long?) : ImageResolution

    data class Ambiguous(val candidates: List<String>) : ImageResolution

    data object NotFound : ImageResolution
}

class VaultLinkResolver(
    private val allEntries: suspend (repoId: String) -> List<RepoEntryEntity>,
) {

    /* ── 笔记链接 ───────────────────────────────────────────── */

    suspend fun resolveNote(repoId: String, rawTarget: String, heading: String?, currentPath: String?): LinkResolution {
        val target = normalize(rawTarget)
        if (target.isEmpty()) return LinkResolution.SameNote(heading)
        val notes = allEntries(repoId).filter { it.kind == EntryKind.MARKDOWN }
        val currentDir = currentPath?.substringBeforeLast('/', missingDelimiterValue = "")

        // 显式 path 形式（§13）：[[Java/Spring Boot]]
        if (target.contains('/')) {
            val byPath = matchByPath(notes, target, currentDir)
            if (byPath != null) return LinkResolution.Note(byPath.path, heading)
            // path 未命中 → 退回 basename 匹配（Obsidian 行为：按最后一段解析）
            return resolveByBasename(notes, target.substringAfterLast('/'), currentDir, heading)
        }

        return resolveByBasename(notes, target, currentDir, heading)
    }

    private fun resolveByBasename(
        notes: List<RepoEntryEntity>,
        basename: String,
        currentDir: String?,
        heading: String?,
    ): LinkResolution {
        // 1. 同目录精确匹配（§12 优先级 1）
        if (currentDir != null) {
            val sameDir = notes.filter { it.parentPath == currentDir && nameMatches(it.name, basename) }
            if (sameDir.size == 1) return LinkResolution.Note(sameDir.first().path, heading)
            if (sameDir.size > 1) return LinkResolution.Ambiguous(sameDir.map { it.path })
        }
        // 2. 全局 basename 精确匹配（唯一才命中；多重命中不随机选，§12）
        val global = notes.filter { nameMatches(it.name, basename) }
        return when {
            global.size == 1 -> LinkResolution.Note(global.first().path, heading)
            global.isEmpty() -> LinkResolution.NotFound
            else -> LinkResolution.Ambiguous(global.map { it.path })
        }
    }

    /** path 形式匹配：相对 Vault 根（优先）与相对当前目录，允许省略 .md（§13）；`..` 段按相对路径归一化。 */
    private fun matchByPath(notes: List<RepoEntryEntity>, target: String, currentDir: String?): RepoEntryEntity? {
        val candidates = buildList {
            add(target)
            if (!target.endsWith(".md", ignoreCase = true)) add("$target.md")
            if (currentDir != null && currentDir.isNotEmpty()) {
                add(normalizePath("$currentDir/$target"))
                val withMd = "$target.md"
                add(normalizePath("$currentDir/$withMd"))
            }
        }
        candidates.forEach { candidate ->
            if (candidate != null) {
                notes.firstOrNull { it.path.equals(candidate, ignoreCase = true) }?.let { return it }
            }
        }
        return null
    }

    /* ── 图片附件（§26）────────────────────────────────────── */

    suspend fun resolveImage(repoId: String, rawTarget: String, currentPath: String?): ImageResolution {
        val target = normalize(rawTarget)
        val images = allEntries(repoId).filter { it.kind == EntryKind.IMAGE }
        val currentDir = currentPath?.substringBeforeLast('/', missingDelimiterValue = "")

        if (target.contains('/')) {
            val byPath = matchImagePath(images, target, currentDir)
            if (byPath != null) return ImageResolution.Found(byPath.path, byPath.blobSha, byPath.size)
        }
        val basename = target.substringAfterLast('/')
        if (currentDir != null) {
            val sameDir = images.filter { it.parentPath == currentDir && nameMatches(it.name, basename) }
            if (sameDir.size == 1) return ImageResolution.Found(sameDir.first().path, sameDir.first().blobSha, sameDir.first().size)
            if (sameDir.size > 1) return ImageResolution.Ambiguous(sameDir.map { it.path })
        }
        val global = images.filter { nameMatches(it.name, basename) }
        return when {
            global.size == 1 -> ImageResolution.Found(global.first().path, global.first().blobSha, global.first().size)
            global.isEmpty() -> ImageResolution.NotFound
            else -> ImageResolution.Ambiguous(global.map { it.path })
        }
    }

    private fun matchImagePath(images: List<RepoEntryEntity>, target: String, currentDir: String?): RepoEntryEntity? {
        val candidates = buildList {
            add(target)
            if (currentDir != null && currentDir.isNotEmpty()) {
                add(normalizePath("$currentDir/$target"))
            }
        }
        candidates.forEach { candidate ->
            if (candidate != null) {
                images.firstOrNull { it.path.equals(candidate, ignoreCase = true) }?.let { return it }
            }
        }
        return null
    }

    /** 相对路径归一化：解析 `.` 与 `..` 段（如 `工作/../附件/a.png` → `附件/a.png`）；越界返回 null。 */
    private fun normalizePath(path: String): String? {
        val stack = mutableListOf<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else return null
                else -> stack += seg
            }
        }
        return stack.joinToString("/")
    }

    /** Markdown 原生图片 `![alt](relative.png)` 的相对路径解析（§31）：Vault 内 → Found；外部 URL → NotFound。 */
    suspend fun resolveRelativeImagePath(repoId: String, url: String, currentPath: String?): ImageResolution {
        if (url.startsWith("http://") || url.startsWith("https://")) return ImageResolution.NotFound
        return resolveImage(repoId, url.removePrefix("./"), currentPath)
    }

    /* ── 共用 ──────────────────────────────────────────────── */

    /** 按精确仓库路径取条目（图片查看器等已持有完整 path 的调用方；Tree path 是规范形式，精确匹配）。 */
    suspend fun findEntry(repoId: String, path: String): RepoEntryEntity? =
        allEntries(repoId).firstOrNull { it.path == path }

    private fun normalize(raw: String): String = raw.trim().removePrefix("/").removePrefix("./").trim()

    /** 文件名匹配：先精确，再大小写不敏感；省略 .md 时补齐（§13）。 */
    private fun nameMatches(entryName: String, basename: String): Boolean {
        if (entryName == basename) return true
        if (entryName.equals(basename, ignoreCase = true)) return true
        if (!basename.endsWith(".md", ignoreCase = true)) {
            return entryName.equals("$basename.md", ignoreCase = true)
        }
        return false
    }
}
