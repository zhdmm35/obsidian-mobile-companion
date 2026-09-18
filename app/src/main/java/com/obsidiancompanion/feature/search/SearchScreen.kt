package com.obsidiancompanion.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.R
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.AppIconButton
import com.obsidiancompanion.core.ui.AppChip
import com.obsidiancompanion.core.ui.AppHorizontalDivider
import com.obsidiancompanion.core.ui.EmptyState
import com.obsidiancompanion.core.ui.SectionHeader
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 正文搜索输入防抖：文件名过滤是内存即时，正文要读缓存文件，延迟合键。 */
private const val CONTENT_SEARCH_DEBOUNCE_MS = 250L

/**
 * 搜索（§27 扩展）：文件名即时过滤（Tree Cache 内存，434 篇规模即时）+ 正文匹配（已缓存笔记，防抖异步）。
 * 正文只覆盖 ContentCache 已缓存的笔记（即打开过的）：按需加载设计（§59/§62）下手机上没有未读笔记的正文。
 * 最近搜索 Room 持久化（§28）：提交搜索或点击结果时记录，同 query 更新时间。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel : ViewModel() {

    var queryField by mutableStateOf(TextFieldValue(""))
        private set

    var results by mutableStateOf<List<RepoEntryEntity>>(emptyList())
        private set

    /** 正文匹配结果（含已缓存正文的笔记；展示时与文件名命中去重，见 SearchScreen） */
    var contentResults by mutableStateOf<List<ContentMatch>>(emptyList())
        private set

    /** Room 里的最近搜索（最多 10 条，展示前 6） */
    var recentQueries by mutableStateOf<List<String>>(emptyList())
        private set

    var hasIndex by mutableStateOf(false)
        private set

    private var tree: List<RepoEntryEntity> = emptyList()
    private var contentJob: kotlinx.coroutines.Job? = null

    val query: String get() = queryField.text

    init {
        viewModelScope.launch {
            AppGraph.settings.flow.flatMapLatest { s ->
                val repoId = s.repoId
                kotlinx.coroutines.flow.combine(
                    if (repoId != null) AppGraph.indexRepository.observeTree(repoId) else flowOf(emptyList()),
                    if (repoId != null) {
                        AppGraph.database.recentSearchDao().observeRecent(repoId, 10)
                    } else {
                        flowOf(emptyList<com.obsidiancompanion.data.metadata.entities.RecentSearchEntity>())
                    },
                ) { t, recent -> t to recent }
            }.collect { (t, recent) ->
                tree = t
                hasIndex = t.isNotEmpty()
                recentQueries = recent.map { it.query }
                onQueryChanged()
            }
        }
    }

    fun onQueryChange(value: TextFieldValue) {
        queryField = value
        onQueryChanged()
    }

    val isIdle: Boolean get() = query.isBlank()

    /** IME「搜索」动作 → 记录最近搜索。 */
    fun onSearchSubmitted() = recordRecent(query)

    /** 点击结果 → 记录最近搜索。 */
    fun onResultOpened() = recordRecent(query)

    fun applyRecent(recent: String) {
        queryField = TextFieldValue(recent, TextRange(recent.length))
        recordRecent(recent)
        onQueryChanged()
    }

    fun clear() {
        queryField = TextFieldValue("")
        onQueryChanged()
    }

    /** 查询或 tree 变化的统一入口：文件名即时重算 + 正文防抖重排。 */
    private fun onQueryChanged() {
        recompute()
        scheduleContentSearch()
    }

    private fun recompute() {
        val q = query.trim()
        results = if (q.isEmpty()) {
            emptyList()
        } else {
            tree.filter { it.kind == EntryKind.MARKDOWN && it.name.removeSuffix(".md").contains(q, ignoreCase = true) }
        }
    }

    /**
     * 正文搜索：取消旧 job → 防抖 → 重扫缓存后匹配；空 query 直接清空。
     * 每次都重扫（不缓存快照）：ContentCache 没有失效信号，用户打开新笔记后回到
     * 本页时 ViewModel 仍存活，缓存旧快照会把新缓存的正文永远挡在结果外。
     * 434 篇规模的单次 IO 重扫在防抖后可接受（与 Reader deadLinks 扫描同量级）。
     */
    private fun scheduleContentSearch() {
        contentJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            contentResults = emptyList()
            return
        }
        contentJob = viewModelScope.launch {
            delay(CONTENT_SEARCH_DEBOUNCE_MS)
            val snapshot = buildContentSnapshot()
            contentResults = withContext(Dispatchers.Default) { searchContents(snapshot, q) }
        }
    }

    /** 快照 = tree 中已缓存正文的 MARKDOWN 条目（按 blobSha 寻址读取）；未缓存的笔记不参与。 */
    private suspend fun buildContentSnapshot(): List<Pair<RepoEntryEntity, String>> =
        withContext(Dispatchers.IO) {
            tree.filter { it.kind == EntryKind.MARKDOWN }.mapNotNull { entry ->
                AppGraph.contentCache.get(entry.blobSha)?.let { bytes -> entry to String(bytes, Charsets.UTF_8) }
            }
        }

    private fun recordRecent(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            val repoId = AppGraph.settings.flow.first().repoId ?: return@launch
            AppGraph.database.recentSearchDao().record(repoId, q, System.currentTimeMillis())
        }
    }
}

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val results = viewModel.results
    // 正文命中与文件名命中同篇去重：文件名区已能到达的笔记不再重复展示；
    // remember 避免每次重组重算 O(n×m) 过滤
    val contentMatches = remember(results, viewModel.contentResults) {
        viewModel.contentResults.filter { match -> results.none { it.path == match.entry.path } }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回 + 搜索输入框
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = AppSpacing.screenPaddingHorizontal, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppIconButton(icon = AppIcons.Back, contentDescription = "返回", onClick = onBack)
            // 搜索输入框（原型 .sfield-in：44dp / 暖白底 / 描边 / 自动聚焦）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(AppShapes.medium)
                    .background(AppColors.surface)
                    .border(1.dp, AppColors.borderStrong, AppShapes.medium)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(AppIcons.Search, contentDescription = null, tint = AppColors.textMeta, modifier = Modifier.size(16.dp))
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        value = viewModel.queryField,
                        onValueChange = viewModel::onQueryChange,
                        textStyle = AppTypography.bodyBase.copy(color = AppColors.textPrimary),
                        cursorBrush = SolidColor(AppColors.accent),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.onSearchSubmitted() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        decorationBox = { inner ->
                            Box {
                                if (viewModel.query.isEmpty()) {
                                    Text("搜索文件名或正文", style = AppTypography.bodyBase, color = AppColors.textMeta)
                                }
                                inner()
                            }
                        },
                    )
                }
                if (viewModel.query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(AppShapes.pill)
                            .clickable(onClick = viewModel::clear),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AppIcons.Close, contentDescription = "清空", tint = AppColors.textMeta, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        when {
            viewModel.isIdle -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = AppSpacing.screenBottomPadding),
            ) {
                if (viewModel.recentQueries.isNotEmpty()) {
                    SectionHeader("最近搜索")
                    Row(
                        modifier = Modifier.padding(
                            start = AppSpacing.screenPaddingHorizontal,
                            end = AppSpacing.screenPaddingHorizontal,
                            top = 6.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    ) {
                        viewModel.recentQueries.take(6).forEach { recent ->
                            AppChip(text = recent, onClick = { viewModel.applyRecent(recent) })
                        }
                    }
                }
                EmptyState(
                    icon = AppIcons.Search,
                    title = "按文件名与正文搜索",
                    subtitle = if (viewModel.hasIndex) "输入关键词，文件名即时匹配；正文匹配覆盖打开过的笔记" else "还没有仓库索引，联网刷新一次后即可搜索",
                )
            }
            results.isEmpty() && contentMatches.isEmpty() -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = AppSpacing.screenBottomPadding),
            ) {
                EmptyState(
                    illustration = R.drawable.spot_search,
                    title = "没有找到「${viewModel.query.trim()}」相关的笔记",
                    subtitle = "试试更短的关键词；正文匹配只覆盖打开过的笔记",
                )
            }
            else -> {
                // 结果数量不设上限，走 LazyColumn（只组合可见行；与文件列表同一形态）
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = AppSpacing.screenBottomPadding),
                ) {
                    if (results.isNotEmpty()) {
                        item { SectionHeader("文件名匹配 · ${results.size}") }
                        itemsIndexed(results, key = { _, entry -> "n:${entry.path}" }) { index, entry ->
                            if (index > 0) AppHorizontalDivider()
                            Row(
                                // 不用 fadeUp：LazyColumn 行滚出/滚入会重置 remember，入场动画每次滚动都重放
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.onResultOpened()
                                        onOpenNote(entry.path)
                                    }
                                    .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = AppSpacing.listRowVertical),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        entry.name.removeSuffix(".md"),
                                        style = AppTypography.rowTitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        entry.path,
                                        style = AppTypography.caption,
                                        color = AppColors.textTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(AppIcons.ChevronRight, contentDescription = null, tint = AppColors.textMeta, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    if (contentMatches.isNotEmpty()) {
                        item { SectionHeader("正文匹配 · ${contentMatches.size}") }
                        itemsIndexed(contentMatches, key = { _, match -> "c:${match.entry.path}" }) { index, match ->
                            if (index > 0) AppHorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.onResultOpened()
                                        onOpenNote(match.entry.path)
                                    }
                                    .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = AppSpacing.listRowVertical),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        match.entry.name.removeSuffix(".md"),
                                        style = AppTypography.rowTitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    // 摘要代替路径：正文命中的区分度在内容本身
                                    Text(
                                        match.snippet,
                                        style = AppTypography.caption,
                                        color = AppColors.textTertiary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(AppIcons.ChevronRight, contentDescription = null, tint = AppColors.textMeta, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
