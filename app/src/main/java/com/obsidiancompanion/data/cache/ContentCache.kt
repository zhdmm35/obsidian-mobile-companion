package com.obsidiancompanion.data.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Markdown 正文缓存（§29-31）：filesDir/content_cache/，按 Git blob SHA 分层存储（如 ab/<sha>）。
 * 同一 SHA 即同一内容版本 —— 远端 SHA 变化后旧缓存自然失效，不存在覆盖/被覆盖问题。
 * 缓存是 disposable 的：可删可重建，不参与任何同步语义。Phase 3 不做自动淘汰（§60）。
 */
class ContentCache(baseDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "content_cache"))

    private val dir: File = baseDir

    private fun fileFor(sha: String): File {
        require(sha.length >= 3 && sha.all { it.isLetterOrDigit() }) { "bad sha: $sha" }
        return File(File(dir, sha.substring(0, 2)), sha.substring(2))
    }

    suspend fun get(sha: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = fileFor(sha)
        if (f.isFile) f.readBytes() else null
    }

    suspend fun put(sha: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val f = fileFor(sha)
        if (f.isFile) return@withContext // 内容寻址：同 SHA 已缓存则跳过
        val parent = f.parentFile ?: return@withContext
        parent.mkdirs()
        val tmp = File(parent, "${f.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            f.writeBytes(bytes) // rename 失败（如被占用）时退化为直写
        }
    }

    suspend fun exists(sha: String): Boolean = withContext(Dispatchers.IO) { fileFor(sha).isFile }

    suspend fun delete(sha: String): Unit = withContext(Dispatchers.IO) {
        fileFor(sha).delete()
    }

    /** 清空全部正文缓存（§61）：不影响 Token / 仓库选择 / Tree Cache / 收藏 / 阅读记录。 */
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { shard ->
            shard.listFiles()?.forEach { it.delete() }
            shard.delete()
        }
    }

    /** 缓存统计（bytes = 全部文件总字节；count = 正式条目数，排除 .tmp）。 */
    data class Stat(val bytes: Long, val count: Int)

    /** 单次目录遍历同时得到总字节与条目数（SyncScreen 两处展示共用，不再各走一遍）。 */
    suspend fun stat(): Stat = withContext(Dispatchers.IO) {
        var bytes = 0L
        var count = 0
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                bytes += f.length()
                if (!f.name.endsWith(".tmp")) count++
            }
        }
        Stat(bytes, count)
    }

    /** 缓存总字节数。 */
    suspend fun size(): Long = stat().bytes

    /** 缓存条目数（SyncScreen「缓存笔记」）。 */
    suspend fun count(): Int = stat().count
}
