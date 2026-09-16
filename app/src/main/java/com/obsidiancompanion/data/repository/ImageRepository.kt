package com.obsidiancompanion.data.repository

import com.obsidiancompanion.data.cache.ContentCache
import com.obsidiancompanion.data.github.GitHubRemoteDataSource
import com.obsidiancompanion.data.github.GitHubResult
import com.obsidiancompanion.data.network.NetworkMonitor
import com.obsidiancompanion.data.settings.SettingsRepository
import com.obsidiancompanion.model.DomainError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * 图片数据流（Phase 4 §25-§28）：
 * Tree（Room）→ blob SHA → SHA Content Cache 命中？→ 未命中 GitHub 拉取字节 → 缓存。
 * 图片与 Markdown 共用同一 SHA 缓存（§27），清除缓存两者一起删（§64）。
 * 不建完整 attachments mirror；认证与缓存策略由数据层控制，Coil 只负责解码展示。
 */
sealed interface ImageResult {
    /** 字节就绪（含来源标记，用于「离线缓存」提示逻辑）。path = 解析后的仓库相对路径（查看器/同目录滑动用）。 */
    data class Ready(val bytes: ByteArray, val fromCache: Boolean, val path: String) : ImageResult

    /** Tree 中没有该目标（§29 Missing）——不是“文件不存在于磁盘”，是 Vault 里就没有。 */
    data object Missing : ImageResult

    /** Tree 有目标但字节未缓存且当前离线（§56：不能说“文件不存在”）。 */
    data object OfflineNotCached : ImageResult

    /** 同名附件多份，V1 无法确定目标（§12 歧义语义）。 */
    data object Ambiguous : ImageResult

    data class Error(val error: DomainError) : ImageResult
}

class ImageRepository(
    private val resolver: VaultLinkResolver,
    private val remote: GitHubRemoteDataSource,
    private val cache: ContentCache,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
) {

    /**
     * 按 Obsidian 目标串加载（`![[a.png]]` 的 target）。
     * currentPath 用于同目录优先解析（§26）。
     */
    suspend fun loadImage(target: String, currentPath: String?): ImageResult {
        val repoId = settings.flow.firstOrNull()?.repoId ?: return ImageResult.Missing
        return when (val resolution = resolver.resolveImage(repoId, target, currentPath)) {
            is ImageResolution.Found -> fetchBytes(resolution)
            is ImageResolution.Ambiguous -> ImageResult.Ambiguous
            ImageResolution.NotFound -> ImageResult.Missing
        }
    }

    /** Markdown 原生图片（§31）：Vault 内相对路径走同一管线；外部 URL 由调用方决定（V1 保留占位）。 */
    suspend fun loadNativeImage(url: String, currentPath: String?): ImageResult {
        if (url.startsWith("http://") || url.startsWith("https://")) return ImageResult.Missing
        return loadImage(url.removePrefix("./"), currentPath)
    }

    /**
     * 按精确仓库路径加载（图片查看器：Files 页点开 / Reader 图片放大）。
     * 与 loadImage 共用同一 fetchBytes 管线；Tree 里没有该路径 → Missing。
     */
    suspend fun loadByPath(path: String): ImageResult {
        val repoId = settings.flow.firstOrNull()?.repoId ?: return ImageResult.Missing
        val entry = resolver.findEntry(repoId, path) ?: return ImageResult.Missing
        return fetchBytes(ImageResolution.Found(entry.path, entry.blobSha, entry.size))
    }

    private suspend fun fetchBytes(found: ImageResolution.Found): ImageResult = withContext(Dispatchers.IO) {
        cache.get(found.blobSha)?.let { return@withContext ImageResult.Ready(it, fromCache = true, path = found.path) }
        if (!network.isOnline) return@withContext ImageResult.OfflineNotCached
        val s = settings.flow.firstOrNull()
        val owner = s?.owner ?: return@withContext ImageResult.Missing
        val repo = s.repo ?: return@withContext ImageResult.Missing
        when (val r = remote.getRawFile(owner, repo, found.path)) {
            is GitHubResult.Ok -> {
                cache.put(found.blobSha, r.value)
                ImageResult.Ready(r.value, fromCache = false, path = found.path)
            }
            is GitHubResult.NotModified -> ImageResult.Missing // raw 内容端点不会 304；防御分支
            is GitHubResult.Fail -> when (r.error) {
                DomainError.NetworkUnavailable -> ImageResult.OfflineNotCached
                else -> ImageResult.Error(r.error)
            }
        }
    }
}
