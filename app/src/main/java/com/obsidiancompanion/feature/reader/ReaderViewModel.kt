package com.obsidiancompanion.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.data.repository.LinkResolution
import com.obsidiancompanion.data.repository.NoteOpenResult
import com.obsidiancompanion.data.repository.RefreshOutcome
import com.obsidiancompanion.model.DomainError
import com.obsidiancompanion.model.markdown.MdDocument
import com.obsidiancompanion.model.markdown.MdInline
import com.obsidiancompanion.util.Format
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/** Reader 状态（§49）：Loading / Content / OfflineCached(含于 Content) / OfflineNotCached / Error。 */
sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Content(
        val path: String,
        val title: String,
        val folder: String,
        val document: MdDocument,
        val markdown: String,
        val fromCache: Boolean,
        /** 离线 + 缓存版本 → 弱提示「显示的是缓存版本」，不切 Error（§49）。 */
        val showOfflineHint: Boolean,
        val sizeLabel: String,
        val blobSha: String,
        val changedLabel: String?,
    ) : ReaderUiState

    /** 从未缓存 + 离线（§5 专用提示文案）。 */
    data object OfflineNotCached : ReaderUiState

    data class Error(val error: DomainError) : ReaderUiState
}

/**
 * Reader 数据流（§35）：NoteRepository.openNote(path) → 缓存命中秒开 / 未命中 GitHub 拉取。
 * 观察 Tree entry 变化：blob SHA 更新时静默重取新正文（§50）。
 */
class ReaderViewModel : ViewModel() {

    var state by mutableStateOf<ReaderUiState>(ReaderUiState.Loading)
        private set

    /**
     * 收藏态独立于 Content（整文档 state）：切换收藏只翻转这个 Boolean，
     * 不再 copy 含整篇 AST 的 Content 触发正文重组；静默重取正文时也天然保留。
     */
    var isFavorite by mutableStateOf(false)
        private set

    private var loadedPath: String? = null
    private var lastSha: String? = null
    private var observingPath: String? = null
    private var favoriteJob: kotlinx.coroutines.Job? = null

    /** 由 Screen 在进入 / 路径变化时调用。 */
    fun load(path: String) {
        if (loadedPath == path && state !is ReaderUiState.Error) return
        loadedPath = path
        fetch(path)
        observeEntry(path)
    }

    fun retry() {
        loadedPath?.let { fetch(it) }
    }

    /** silent=true：SHA 变化后的静默重取 —— 保留旧内容直到新内容就绪，失败也不打断当前展示。 */
    private fun fetch(path: String, silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) state = ReaderUiState.Loading
            when (val r = AppGraph.noteRepository.openNote(path)) {
                is NoteOpenResult.Content -> {
                    lastSha = r.entry.blobSha
                    // §51：内容真正可展示后才计入阅读
                    AppGraph.noteRepository.markRead(path)
                    state = ReaderUiState.Content(
                        path = path,
                        title = r.entry.name.removeSuffix(".md"),
                        folder = r.entry.parentPath ?: "根目录",
                        document = r.document,
                        markdown = r.markdown,
                        fromCache = r.fromCache,
                        showOfflineHint = r.fromCache && !AppGraph.network.isOnline,
                        sizeLabel = Format.bytes(r.entry.size),
                        blobSha = r.entry.blobSha,
                        changedLabel = Format.relativeTime(r.entry.observedChangedAt),
                    )
                    observeFavorite(path)
                }
                is NoteOpenResult.OfflineNotCached -> if (!silent) state = ReaderUiState.OfflineNotCached
                is NoteOpenResult.Error -> if (!silent) state = ReaderUiState.Error(r.error)
            }
        }
    }

    /** Tree 刷新发现该 path 的 blobSha 变化 → 静默重取正文（§50），不打断当前展示。 */
    private fun observeEntry(path: String) {
        if (observingPath == path) return
        observingPath = path
        viewModelScope.launch {
            val repoId = AppGraph.settings.flow.firstOrNull()?.repoId ?: return@launch
            AppGraph.indexRepository.observeEntry(repoId, path).collect { entry ->
                val content = state as? ReaderUiState.Content ?: return@collect
                if (entry != null && entry.blobSha != lastSha) {
                    lastSha = entry.blobSha
                    fetch(path, silent = true)
                } else if (entry == null && content.path == path) {
                    state = ReaderUiState.Error(DomainError.NotFound)
                }
            }
        }
    }

    /** 每次成功加载只保留一个观察协程（旧路径/上次 fetch 的取消掉，避免累积与跨笔记串写）。 */
    private fun observeFavorite(path: String) {
        favoriteJob?.cancel()
        favoriteJob = viewModelScope.launch {
            val repoId = AppGraph.settings.flow.firstOrNull()?.repoId ?: return@launch
            AppGraph.database.noteMetadataDao().observeOne(repoId, path).collect { meta ->
                isFavorite = meta?.isFavorite == true
            }
        }
    }

    fun toggleFavorite(): Boolean {
        val content = state as? ReaderUiState.Content ?: return false
        val next = !isFavorite
        isFavorite = next
        viewModelScope.launch { AppGraph.noteRepository.setFavorite(content.path, next) }
        return next
    }

    /** 「刷新此笔记」（§54）：检查最新 Tree → SHA 更新则获取新内容。 */
    fun refreshNote(onMessage: (String) -> Unit) {
        val path = loadedPath ?: return
        viewModelScope.launch {
            when {
                !AppGraph.network.isOnline -> onMessage("当前离线，联网后可刷新")
                else -> {
                    val before = lastSha
                    // 用户手动「刷新此笔记」：强制请求（Phase 6B §5）
                    when (val outcome = AppGraph.indexRepository.refreshTree(force = true)) {
                        is RefreshOutcome.NotModified -> onMessage("此笔记已是最新")
                        is RefreshOutcome.Success -> {
                            val repoId = AppGraph.settings.flow.firstOrNull()?.repoId
                            val entry = repoId?.let { AppGraph.indexRepository.getEntry(it, path) }
                            if (entry != null && entry.blobSha != before) {
                                fetch(path)
                                onMessage("已获取最新内容")
                            } else {
                                onMessage("此笔记已是最新")
                            }
                        }
                        is RefreshOutcome.Failed -> onMessage("刷新失败：${outcome.error.userMessage()}")
                    }
                }
            }
        }
    }

    /**
     * WikiLink 点击（§14-§19/§55）：Room Tree 解析 → 跳转 / 同笔记滚动 / Snackbar。
     * Tree existence ≠ content cache existence（§55）：目标笔记未缓存时照常进入 Reader，
     * 由 Reader 自己呈现「当前离线 · 这篇笔记还没有缓存到手机」。
     */
    fun onWikiLink(
        wiki: MdInline.WikiLink,
        onOpenNote: (path: String, heading: String?) -> Unit,
        onSameNoteAnchor: (heading: String) -> Unit,
        onMessage: (String) -> Unit,
    ) {
        if (wiki.embed && wiki.target.isEmpty() && wiki.heading == null) {
            onMessage("嵌入目标为空")
            return
        }
        viewModelScope.launch {
            val repoId = AppGraph.settings.flow.firstOrNull()?.repoId
            if (repoId == null) {
                onMessage("还没有连接仓库")
                return@launch
            }
            when (val r = AppGraph.linkResolver.resolveNote(repoId, wiki.target, wiki.heading, loadedPath)) {
                is LinkResolution.SameNote -> {
                    val heading = r.heading
                    if (heading == null) onMessage("锚点为空")
                    else onSameNoteAnchor(heading) // §19：当前 Reader 内滚动，不 push 新实例
                }
                is LinkResolution.Note -> onOpenNote(r.path, r.heading)
                is LinkResolution.Ambiguous -> onMessage("同名笔记有多份，暂无法确定目标")
                LinkResolution.NotFound -> onMessage("没有找到这篇笔记") // §15：不 crash、不建新文件
            }
        }
    }
}

/** DomainError → 用户能看懂的一句话（§64-67）。 */
fun DomainError.userMessage(): String = when (this) {
    DomainError.Unauthorized -> "GitHub 登录信息已失效，请在设置中重新设置 Token"
    DomainError.Forbidden -> "没有权限访问这个仓库"
    DomainError.NotFound -> "笔记不存在或已被删除"
    DomainError.RateLimited -> "GitHub 接口限流，请稍后再试"
    DomainError.NetworkUnavailable -> "网络不可用，请检查网络后重试"
    DomainError.ServerError -> "GitHub 服务暂时不可用，请稍后再试"
    DomainError.MalformedResponse -> "GitHub 返回了无法解析的数据"
    DomainError.Conflict -> "这篇笔记在别处有新修改，存在冲突"
    DomainError.Unknown -> "出了点问题，请稍后再试"
}
