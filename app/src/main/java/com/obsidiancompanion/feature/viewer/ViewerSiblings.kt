package com.obsidiancompanion.feature.viewer

import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity

/**
 * 同目录图片列表（查看器左右滑动）：仅 IMAGE 类型、按名称排序（与 Files 页同序）。
 * 当前图片不在结果里（无索引 / kind 变化）时退化为仅自身，保证查看器至少可打开。
 */
fun siblingImagePaths(tree: List<RepoEntryEntity>, path: String): List<String> {
    val parent = path.substringBeforeLast('/', "").ifEmpty { null }
    val siblings = tree
        .filter { it.kind == EntryKind.IMAGE && it.parentPath == parent }
        .sortedBy { it.name }
        .map { it.path }
    return if (path in siblings) siblings else listOf(path)
}
