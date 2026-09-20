package com.obsidiancompanion.data.repository

import com.obsidiancompanion.data.cache.ContentCache
import com.obsidiancompanion.data.markdown.MarkdownParser
import com.obsidiancompanion.data.metadata.AppDatabase
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.model.markdown.MdBlock
import com.obsidiancompanion.model.markdown.MdDocument
import com.obsidiancompanion.model.markdown.MdInline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** 文件夹离线副本只由现有 SHA 缓存组成，状态按当前 Tree 和实际缓存重新计算。 */
class OfflineFolderManager(
    private val db: AppDatabase,
    private val cache: ContentCache,
    private val notes: NoteRepository,
    private val images: ImageRepository,
    private val resolver: VaultLinkResolver,
    private val scope: CoroutineScope,
) {
    data class Progress(val done: Int, val total: Int, val failed: Int)
    data class FolderState(val cached: Int, val total: Int, val bytes: Long, val downloading: Progress? = null, val failed: Boolean = false) {
        val label: String get() = when {
            downloading != null -> "下载中 ${downloading.done}/${downloading.total}"
            total == 0 -> "未下载"
            cached == total -> "已下载"
            failed -> "部分失败 $cached/$total"
            cached == 0 -> "未下载"
            else -> "部分已缓存 $cached/$total"
        }
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress
    private val failures = ConcurrentHashMap.newKeySet<String>()

    private fun key(repoId: String, folder: String) = "$repoId\n$folder"

    private fun members(folder: String, tree: List<RepoEntryEntity>): List<RepoEntryEntity> =
        offlineFolderMembers(folder, tree)

    /** 引用图片可能存放在文件夹外；只收集 Reader 实际能解析的 Vault 图片。 */
    private suspend fun targets(folder: String, tree: List<RepoEntryEntity>): List<RepoEntryEntity> {
        val selected = members(folder, tree).associateByTo(linkedMapOf()) { it.path }
        for (note in selected.values.toList().filter { it.kind == EntryKind.MARKDOWN }) {
            val bytes = cache.get(note.blobSha) ?: continue
            val doc = MarkdownParser.parse(String(bytes, Charsets.UTF_8))
            for (target in imageTargets(doc)) {
                val found = resolver.resolveImage(tree, target, note.path)
                if (found is ImageResolution.Found) {
                    tree.firstOrNull { it.path == found.path }?.let { selected[it.path] = it }
                }
            }
        }
        return selected.values.toList()
    }

    suspend fun state(repoId: String, folder: String, tree: List<RepoEntryEntity>): FolderState {
        val entries = targets(folder, tree)
        val cached = entries.count { cache.exists(it.blobSha) }
        val bytes = entries.map { it.blobSha }.distinct().sumOf { cache.length(it) }
        return FolderState(cached, entries.size, bytes, _progress.value[key(repoId, folder)], key(repoId, folder) in failures)
    }

    fun download(repoId: String, folder: String) {
        val id = key(repoId, folder)
        if (jobs[id]?.isActive == true) return
        failures.remove(id)
        jobs[id] = scope.launch {
            var done = 0
            var failed = 0
            fun report(total: Int) { _progress.update { it + (id to Progress(done, total, failed)) } }
            try {
                val tree = db.repoEntryDao().getAll(repoId)
                val initial = members(folder, tree)
                report(initial.size)
                for (entry in initial) {
                    if (!cache.exists(entry.blobSha)) {
                        val ok = when (entry.kind) {
                            EntryKind.MARKDOWN -> notes.openNote(entry.path) is NoteOpenResult.Content
                            EntryKind.IMAGE -> images.loadByPath(entry.path) is ImageResult.Ready
                            else -> true
                        }
                        if (!ok) failed++
                    }
                    done++
                    report(initial.size)
                }
                // 笔记正文下载完成后才能发现跨目录引用的图片。
                val initialPaths = initial.map { it.path }.toSet()
                val extras = targets(folder, tree).filter { it.path !in initialPaths }
                report(initial.size + extras.size)
                for (entry in extras) {
                    if (!cache.exists(entry.blobSha) && images.loadByPath(entry.path) !is ImageResult.Ready) failed++
                    done++
                    report(initial.size + extras.size)
                }
                if (failed > 0) failures.add(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures.add(id)
            } finally {
                _progress.update { it - id }
                jobs.remove(id)
            }
        }
    }

    /** SHA 可能被其他目录共用；草稿的基准版本也必须保留。 */
    suspend fun clearFolder(repoId: String, folder: String): Int {
        val id = key(repoId, folder)
        jobs[id]?.cancel()
        jobs[id]?.join()
        val tree = db.repoEntryDao().getAll(repoId)
        val selected = targets(folder, tree).map { it.path }.toSet()
        val referencedElsewhere = mutableSetOf<String>()
        for (note in tree.filter { it.kind == EntryKind.MARKDOWN && it.path !in selected }) {
            val bytes = cache.get(note.blobSha) ?: continue
            for (target in imageTargets(MarkdownParser.parse(String(bytes, Charsets.UTF_8)))) {
                val found = resolver.resolveImage(tree, target, note.path)
                if (found is ImageResolution.Found) referencedElsewhere += found.blobSha
            }
        }
        val drafts = db.pendingEditDao().getAll(repoId)
        val draftPaths = drafts.map { it.path }.toSet()
        val protected = tree.filter { it.path !in selected || it.path in draftPaths }
            .map { it.blobSha }.toSet() + drafts.map { it.baseSha } + referencedElsewhere
        val shas = tree.filter { it.path in selected }.map { it.blobSha }.toSet() - protected
        shas.forEach { cache.delete(it) }
        failures.remove(id)
        return shas.size
    }

    suspend fun draftCount(repoId: String, folder: String): Int =
        db.pendingEditDao().getAll(repoId).count { it.path.startsWith("$folder/") }
}

internal fun offlineFolderMembers(folder: String, tree: List<RepoEntryEntity>): List<RepoEntryEntity> =
    tree.filter { it.path.startsWith("$folder/") && it.kind in setOf(EntryKind.MARKDOWN, EntryKind.IMAGE) }

internal fun imageTargets(document: MdDocument): Set<String> {
    val targets = linkedSetOf<String>()
    fun inlines(items: List<MdInline>) {
        for (item in items) when (item) {
            is MdInline.Image -> targets += item.url
            is MdInline.Bold -> inlines(item.children)
            is MdInline.Italic -> inlines(item.children)
            is MdInline.Strike -> inlines(item.children)
            is MdInline.Link -> inlines(item.children)
            else -> Unit
        }
    }
    fun blocks(items: List<MdBlock>) {
        for (item in items) when (item) {
            is MdBlock.Image -> targets += item.url
            is MdBlock.ImageEmbed -> targets += item.target
            is MdBlock.Heading -> inlines(item.inlines)
            is MdBlock.Paragraph -> inlines(item.inlines)
            is MdBlock.BulletList -> item.items.forEach { inlines(it.inlines); blocks(it.children) }
            is MdBlock.OrderedList -> item.items.forEach { inlines(it.inlines); blocks(it.children) }
            is MdBlock.TaskList -> item.items.forEach { inlines(it.inlines); blocks(it.children) }
            is MdBlock.Quote -> blocks(item.children)
            is MdBlock.Callout -> blocks(item.blocks)
            is MdBlock.Table -> { item.headers.forEach(::inlines); item.rows.flatten().forEach(::inlines) }
            else -> Unit
        }
    }
    blocks(document.blocks)
    return targets.filterNot { it.startsWith("http://") || it.startsWith("https://") }.toSet()
}
