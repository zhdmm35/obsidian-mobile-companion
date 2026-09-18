package com.obsidiancompanion.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.R
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.AppHorizontalDivider
import com.obsidiancompanion.core.ui.AppIconButton
import com.obsidiancompanion.core.ui.EmptyState
import com.obsidiancompanion.core.ui.SecondaryButton
import com.obsidiancompanion.data.repository.LinkResolution
import com.obsidiancompanion.model.markdown.MdBlock
import com.obsidiancompanion.model.markdown.MdInline
import com.obsidiancompanion.model.markdown.findHeadingIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * 阅读器（原型 scr-reader）：无标题沉浸顶栏 + NoteHeader + Markdown 块渲染。
 * 数据来自 NoteRepository（缓存秒开 / 联网拉取），状态见 ReaderUiState。
 * Phase 4：WikiLink 跳转 / 同笔记锚点 / 文本选择（§16/§19/§39）。
 */
@Composable
fun ReaderScreen(
    notePath: String,
    anchor: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenNote: (path: String, heading: String?) -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onShowSnackbar: (String) -> Unit,
    viewModel: ReaderViewModel = viewModel(),
) {
    LaunchedEffect(notePath) { viewModel.load(notePath) }

    when (val state = viewModel.state) {
        is ReaderUiState.Loading -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReaderTopBar(onBack = onBack, onMore = null)
            Spacer(Modifier.weight(1f))
            com.obsidiancompanion.feature.sync.SpinningIcon(
                AppIcons.Refresh,
                tint = AppColors.textMeta,
                size = 22.dp,
                contentDescription = null,
            )
            Spacer(Modifier.height(10.dp))
            Text("正在加载……", style = AppTypography.bodySmall, color = AppColors.textTertiary)
            Spacer(Modifier.weight(1f))
        }

        is ReaderUiState.OfflineNotCached -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReaderTopBar(onBack = onBack, onMore = null)
            Spacer(Modifier.weight(1f))
            EmptyState(
                illustration = R.drawable.spot_offline,
                title = "当前离线",
                subtitle = "这篇笔记还没有缓存到手机。\n联网后即可打开。",
            )
            SecondaryButton(text = "重试", onClick = { viewModel.retry() })
            Spacer(Modifier.weight(1f))
        }

        is ReaderUiState.Error -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReaderTopBar(onBack = onBack, onMore = null)
            Spacer(Modifier.weight(1f))
            EmptyState(
                icon = AppIcons.Alert,
                title = "无法加载这篇笔记",
                subtitle = state.error.userMessage(),
            )
            SecondaryButton(text = "重试", onClick = { viewModel.retry() })
            Spacer(Modifier.weight(1f))
        }

        is ReaderUiState.Content -> ReaderContent(
            state = state,
            anchor = anchor,
            onBack = onBack,
            onEdit = onEdit,
            onOpenNote = onOpenNote,
            onOpenImage = onOpenImage,
            onOpenExternalUrl = onOpenExternalUrl,
            onShowSnackbar = onShowSnackbar,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun ReaderTopBar(onBack: () -> Unit, onMore: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconButton(icon = AppIcons.Back, contentDescription = "返回", onClick = onBack)
        Spacer(Modifier.weight(1f))
        if (onMore != null) {
            AppIconButton(icon = AppIcons.More, contentDescription = "更多", onClick = onMore)
        }
    }
}

@Composable
private fun ReaderContent(
    state: ReaderUiState.Content,
    anchor: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenNote: (path: String, heading: String?) -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onShowSnackbar: (String) -> Unit,
    viewModel: ReaderViewModel,
) {
    val clipboard = LocalClipboardManager.current
    var moreSheetVisible by remember { mutableStateOf(false) }
    var fileInfoVisible by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // 同笔记锚点滚动信号（§19：[[#H]] 当前 Reader 内滚动，不 push 新实例）
    var sameNoteAnchor by remember { mutableStateOf<String?>(null) }

    // §15：渲染前批量解析本文全部 WikiLink，未解析的以 muted 色显示（点击仍给 Snackbar）
    val deadLinks by produceState<Set<String>>(emptySet(), state.document, state.path) {
        val repoId = AppGraph.settings.flow.firstOrNull()?.repoId ?: return@produceState
        // produceState 块跑在组合（主线程）上下文：遍历 AST + 逐链接内存扫描整体挪到 Default，
        // 大库（数千 entry）多链接笔记打开时不再占 UI 线程
        value = withContext(Dispatchers.Default) {
            val wikis = mutableListOf<MdInline.WikiLink>()
            fun visitInlines(inlines: List<MdInline>) {
                inlines.forEach { inline ->
                    when (inline) {
                        is MdInline.WikiLink -> if (!inline.embed) wikis += inline
                        is MdInline.Bold -> visitInlines(inline.children)
                        is MdInline.Italic -> visitInlines(inline.children)
                        is MdInline.Strike -> visitInlines(inline.children)
                        is MdInline.Link -> visitInlines(inline.children)
                        else -> Unit
                    }
                }
            }
            fun visitBlocks(blocks: List<MdBlock>) {
                blocks.forEach { block ->
                    when (block) {
                        is MdBlock.Heading -> visitInlines(block.inlines)
                        is MdBlock.Paragraph -> visitInlines(block.inlines)
                        is MdBlock.Quote -> visitBlocks(block.children)
                        is MdBlock.Callout -> visitBlocks(block.blocks)
                        is MdBlock.BulletList -> block.items.forEach { visitInlines(it.inlines); visitBlocks(it.children) }
                        is MdBlock.OrderedList -> block.items.forEach { visitInlines(it.inlines); visitBlocks(it.children) }
                        is MdBlock.TaskList -> block.items.forEach { visitInlines(it.inlines); visitBlocks(it.children) }
                        is MdBlock.Table -> {
                            block.headers.forEach { visitInlines(it) }
                            block.rows.forEach { row -> row.forEach { visitInlines(it) } }
                        }
                        else -> Unit
                    }
                }
            }
            visitBlocks(state.document.blocks)
            // 整篇笔记只取一次 Tree 快照，逐链接内存解析（不再每条链接一次 Room 全表查询）
            val entries = AppGraph.database.repoEntryDao().getAll(repoId)
            val dead = mutableSetOf<String>()
            for (wiki in wikis) {
                when (AppGraph.linkResolver.resolveNote(entries, wiki.target, wiki.heading, state.path)) {
                    LinkResolution.NotFound, is LinkResolution.Ambiguous -> dead += wiki.raw
                    else -> Unit
                }
            }
            dead
        }
    }

    val links = remember(onOpenNote, onOpenExternalUrl, onShowSnackbar) {
        object : ReaderLinkHandler {
            override fun onExternalLink(url: String) = onOpenExternalUrl(url)
            override fun onWikiLink(wiki: MdInline.WikiLink) {
                viewModel.onWikiLink(
                    wiki = wiki,
                    onOpenNote = onOpenNote,
                    onSameNoteAnchor = { sameNoteAnchor = it },
                    onMessage = onShowSnackbar,
                )
            }
        }
    }

    // #锚点 定位（原型 offset 84dp：目标停在顶栏下方；§18 未找到 → 停顶部 + Snackbar）
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(anchor, state.document) {
        if (anchor != null) {
            val index = findHeadingIndex(state.document.blocks, anchor)
            if (index >= 0) {
                listState.scrollToItem(index + 1)
                with(density) { listState.scrollBy((-84.dp).toPx()) }
            } else {
                onShowSnackbar("没有找到目标标题")
            }
        }
    }
    LaunchedEffect(sameNoteAnchor, state.document) {
        val target = sameNoteAnchor ?: return@LaunchedEffect
        val index = findHeadingIndex(state.document.blocks, target)
        if (index >= 0) {
            listState.scrollToItem(index + 1)
            with(density) { listState.scrollBy((-84.dp).toPx()) }
        } else {
            onShowSnackbar("没有找到目标标题")
        }
        sameNoteAnchor = null
    }

    Column(Modifier.fillMaxSize()) {
        ReaderTopBar(onBack = onBack, onMore = { moreSheetVisible = true })

        // §39：正文可长按选择；普通 tap 仍触发 WikiLink / 外链（E2E 验证 §40）
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            // NoteHeader（原型 .rdoc-head）
            item(key = "note-header") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = AppSpacing.readerPaddingHorizontal,
                            end = AppSpacing.readerPaddingHorizontal,
                            top = 8.dp,
                            bottom = 16.dp,
                        ),
                ) {
                    Text(state.title, style = AppTypography.readerTitle)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(AppIcons.FileText, contentDescription = null, tint = AppColors.textTertiary, modifier = Modifier.size(13.dp))
                        val meta = buildString {
                            append(state.folder)
                            if (state.changedLabel != null) append(" · 远端更新于 ${state.changedLabel}")
                        }
                        Text(meta, style = AppTypography.caption, color = AppColors.textTertiary)
                    }
                }
                // §49：离线 + 缓存 → 弱提示，不切 Error
                if (state.showOfflineHint) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = AppSpacing.readerPaddingHorizontal,
                                end = AppSpacing.readerPaddingHorizontal,
                                bottom = 10.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(AppIcons.CloudOff, contentDescription = null, tint = AppColors.textTertiary, modifier = Modifier.size(13.dp))
                        Text("离线 · 显示的是缓存版本", style = AppTypography.caption, color = AppColors.textTertiary)
                    }
                }
                AppHorizontalDivider()
                Spacer(Modifier.height(6.dp))
            }
            items(state.document.blocks.size) { index ->
                ReaderBlockRenderer(
                    block = state.document.blocks[index],
                    links = links,
                    currentNotePath = state.path,
                    deadLinks = deadLinks,
                    onOpenImage = onOpenImage,
                    modifier = Modifier.padding(horizontal = AppSpacing.readerPaddingHorizontal),
                )
            }
            item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }

    if (moreSheetVisible) {
        ReaderMoreSheet(
            noteTitle = state.title,
            isFavorite = viewModel.isFavorite,
            onDismiss = { moreSheetVisible = false },
            onEdit = {
                moreSheetVisible = false
                onEdit() // Phase 5 §4：进入 Editor（编辑已有 Markdown）
            },
            onToggleFavorite = {
                moreSheetVisible = false
                val added = viewModel.toggleFavorite()
                onShowSnackbar(if (added) "已收藏，首页可以直接打开" else "已取消收藏")
            },
            onShowFileInfo = {
                moreSheetVisible = false
                fileInfoVisible = true
            },
            onCopyPath = {
                moreSheetVisible = false
                clipboard.setText(AnnotatedString("/${state.path}"))
                onShowSnackbar("文件路径已复制")
            },
            onRefreshNote = {
                moreSheetVisible = false
                onShowSnackbar("正在刷新 ${state.title}")
                viewModel.refreshNote(onShowSnackbar)
            },
        )
    }

    if (fileInfoVisible) {
        FileInfoSheet(
            title = state.title,
            path = state.path,
            sizeLabel = state.sizeLabel,
            blobSha = state.blobSha,
            frontmatterRaw = state.document.frontmatter?.raw,
            onDismiss = { fileInfoVisible = false },
        )
    }
}
