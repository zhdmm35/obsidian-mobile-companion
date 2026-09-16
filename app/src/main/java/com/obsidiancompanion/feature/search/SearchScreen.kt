package com.obsidiancompanion.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.AppIconButton
import com.obsidiancompanion.core.ui.AppChip
import com.obsidiancompanion.core.ui.EmptyState
import com.obsidiancompanion.core.ui.SectionHeader
import com.obsidiancompanion.core.ui.fadeUp
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.feature.home.DividerList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 搜索（§27）：V1 规则 —— 仅文件名（不含 .md）参与匹配，路径只展示；
 * 基于 Tree Cache 内存过滤（434 篇规模即时）。
 * 最近搜索 Room 持久化（§28）：提交搜索或点击结果时记录，同 query 更新时间。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel : ViewModel() {

    var queryField by mutableStateOf(TextFieldValue(""))
        private set

    var results by mutableStateOf<List<RepoEntryEntity>>(emptyList())
        private set

    /** Room 里的最近搜索（最多 10 条，展示前 6） */
    var recentQueries by mutableStateOf<List<String>>(emptyList())
        private set

    var hasIndex by mutableStateOf(false)
        private set

    private var tree: List<RepoEntryEntity> = emptyList()

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
                recompute()
            }
        }
    }

    fun onQueryChange(value: TextFieldValue) {
        queryField = value
        recompute()
    }

    val isIdle: Boolean get() = query.isBlank()

    /** IME「搜索」动作 → 记录最近搜索。 */
    fun onSearchSubmitted() = recordRecent(query)

    /** 点击结果 → 记录最近搜索。 */
    fun onResultOpened() = recordRecent(query)

    fun applyRecent(recent: String) {
        queryField = TextFieldValue(recent, TextRange(recent.length))
        recordRecent(recent)
        recompute()
    }

    fun clear() {
        queryField = TextFieldValue("")
        recompute()
    }

    private fun recompute() {
        val q = query.trim()
        results = if (q.isEmpty()) {
            emptyList()
        } else {
            tree.filter { it.kind == EntryKind.MARKDOWN && it.name.removeSuffix(".md").contains(q, ignoreCase = true) }
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
                                    Text("搜索笔记文件名", style = AppTypography.bodyBase, color = AppColors.textMeta)
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

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppSpacing.screenBottomPadding),
        ) {
            when {
                viewModel.isIdle -> {
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
                        title = "按文件名搜索",
                        subtitle = if (viewModel.hasIndex) "输入关键词，实时过滤 Vault 中的全部笔记" else "还没有仓库索引，联网刷新一次后即可搜索",
                    )
                }
                results.isEmpty() -> {
                    EmptyState(
                        icon = AppIcons.Search,
                        title = "没有找到「${viewModel.query.trim()}」相关的笔记",
                        subtitle = "试试更短的关键词，或检查文件名",
                    )
                }
                else -> {
                    SectionHeader("搜索结果 · ${results.size}")
                    DividerList(results) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.onResultOpened()
                                    onOpenNote(entry.path)
                                }
                                .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = AppSpacing.listRowVertical)
                                .fadeUp(),
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
            }
        }
    }
}
