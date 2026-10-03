package com.obsidiancompanion.data.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Markdown 正文缓存（§29-31）：filesDir/content_cache/，按 Git blob SHA 分层存储（如 ab/<sha>）。
 * 同一 SHA 即同一内容版本 —— 远端 SHA 变化后旧缓存自然失效，不存在覆盖/被覆盖问题。
 * 缓存是 disposable 的；超额时仅回收未引用的旧版本，不淘汰当前离线内容与草稿基准。
 */
class ContentCache(baseDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "content_cache"))

    private val dir: File = baseDir
    private val mutex = Mutex()

    private fun fileFor(sha: String): File {
        require(sha.length >= 3 && sha.all { it.isLetterOrDigit() }) { "bad sha: $sha" }
        return File(File(dir, sha.substring(0, 2)), sha.substring(2))
    }

    suspend fun get(sha: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = fileFor(sha)
        mutex.withLock { if (f.isFile) f.readBytes() else null }
    }

    suspend fun put(sha: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val f = fileFor(sha)
        mutex.withLock {
            if (f.isFile) return@withLock // 内容寻址：同 SHA 已缓存则跳过
            val parent = f.parentFile ?: return@withLock
            parent.mkdirs()
            val tmp = File(parent, "${f.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                f.writeBytes(bytes) // rename 失败（如被占用）时退化为直写
            }
        }
    }

    suspend fun exists(sha: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { fileFor(sha).isFile }
    }

    suspend fun length(sha: String): Long = withContext(Dispatchers.IO) {
        mutex.withLock { fileFor(sha).takeIf { it.isFile }?.length() ?: 0L }
    }

    suspend fun delete(sha: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { fileFor(sha).delete() }
    }

    /** 清空全部正文缓存（§61）：不影响 Token / 仓库选择 / Tree Cache / 收藏 / 阅读记录。 */
    suspend fun clear(): Unit = clearExcept(emptySet())

    /** 清除可重建缓存，保留待上传草稿对应的基准 blob。 */
    suspend fun clearExcept(protectedShas: Set<String>): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles()?.forEach { shard ->
                shard.listFiles()?.forEach { file ->
                    if (shard.name + file.name !in protectedShas) file.delete()
                }
                shard.delete()
            }
        }
    }

    /** 缓存统计（bytes = 全部文件总字节；count = 正式条目数，排除 .tmp）。 */
    data class Stat(val bytes: Long, val count: Int)

    /** 单次目录遍历同时得到总字节与条目数（SyncScreen 两处展示共用，不再各走一遍）。 */
    suspend fun stat(noteShas: Collection<String>? = null): Stat = withContext(Dispatchers.IO) {
        var bytes = 0L
        var count = 0
        val cachedShas = mutableSetOf<String>()
        mutex.withLock {
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    bytes += f.length()
                    if (!f.name.endsWith(".tmp")) {
                        count++
                        if (noteShas != null) cachedShas += f.parentFile?.name.orEmpty() + f.name
                    }
                }
            }
        }
        Stat(bytes, noteShas?.count { it in cachedShas } ?: count)
    }

    /** 软上限：只回收未引用且至少一天前写入的版本，保护与索引提交并发的新缓存。 */
    suspend fun trimObsolete(
        protectedShas: Set<String>,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        nowMs: Long = System.currentTimeMillis(),
    ): Long = withContext(Dispatchers.IO) {
        require(maxBytes >= 0)
        val context = currentCoroutineContext()
        mutex.withLock {
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            var bytes = files.sumOf { it.length() }
            for (file in files.sortedBy { it.lastModified() }) {
                context.ensureActive()
                if (bytes <= maxBytes) break
                val sha = file.parentFile?.name.orEmpty() + file.name
                if (sha in protectedShas || nowMs - file.lastModified() < 86_400_000L) continue
                val length = file.length()
                if (file.delete()) bytes -= length
            }
            bytes
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024
    }

    /** 缓存总字节数。 */
    suspend fun size(): Long = stat().bytes

    /** 缓存 blob 条目数；界面笔记统计使用 stat(noteShas)。 */
    suspend fun count(): Int = stat().count
}
