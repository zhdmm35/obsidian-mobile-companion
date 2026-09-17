package com.obsidiancompanion.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.asImageBitmap
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.data.repository.ImageResult
import com.obsidiancompanion.data.repository.LinkResolution
import com.obsidiancompanion.model.markdown.MdBlock
import com.obsidiancompanion.model.markdown.MdInline
import com.obsidiancompanion.model.markdown.MdListItem
import com.obsidiancompanion.model.markdown.displayText
import com.obsidiancompanion.model.markdown.plainText
import kotlinx.coroutines.flow.firstOrNull

/**
 * Markdown 块渲染器：输入为 MarkdownParser 产出的正式 AST（MdBlock / MdInline）。
 * Phase 4：WikiLink / Callout / 图片与笔记嵌入 / 嵌套列表引用 —— 全部由 AST 驱动，
 * 渲染层不做字符串识别（§7）。
 */

/** 可点击文本区域：外部链接 / WikiLink 二选一。 */
private data class TapRange(val start: Int, val end: Int, val url: String?, val wiki: MdInline.WikiLink?)

/** Reader 交互回调（由 ReaderContent 注入）。 */
interface ReaderLinkHandler {
    fun onExternalLink(url: String)
    fun onWikiLink(wiki: MdInline.WikiLink)
}

@Composable
fun ReaderBlockRenderer(
    block: MdBlock,
    links: ReaderLinkHandler,
    currentNotePath: String,
    modifier: Modifier = Modifier,
    deadLinks: Set<String> = emptySet(),
    onOpenImage: (String) -> Unit = {},
) {
    when (block) {
        is MdBlock.Heading -> ReaderHeading(block, links, deadLinks, modifier)
        is MdBlock.Paragraph -> InlineText(
            inlines = block.inlines,
            links = links,
            deadLinks = deadLinks,
            modifier = modifier.padding(vertical = 7.dp),
        )
        is MdBlock.BulletList -> ReaderList(
            items = block.items,
            marker = { null },
            links = links,
            currentNotePath = currentNotePath,
            deadLinks = deadLinks,
            onOpenImage = onOpenImage,
            modifier = modifier,
        )
        is MdBlock.OrderedList -> ReaderList(
            items = block.items,
            marker = { index -> "${block.startNumber + index}." },
            links = links,
            currentNotePath = currentNotePath,
            deadLinks = deadLinks,
            onOpenImage = onOpenImage,
            modifier = modifier,
        )
        is MdBlock.TaskList -> ReaderTaskList(block, links, currentNotePath, deadLinks, onOpenImage, modifier)
        is MdBlock.Quote -> ReaderQuote(block, links, currentNotePath, deadLinks, onOpenImage, modifier)
        is MdBlock.Callout -> ReaderCallout(block, links, currentNotePath, deadLinks, onOpenImage, modifier)
        is MdBlock.CodeBlock -> ReaderCodeBlock(block, modifier)
        is MdBlock.Table -> ReaderTable(block, links, deadLinks, modifier)
        is MdBlock.Image -> ReaderNativeImage(block, currentNotePath, onOpenImage, modifier)
        is MdBlock.ImageEmbed -> ReaderImageEmbed(block, currentNotePath, onOpenImage, modifier)
        is MdBlock.EmbeddedNote -> ReaderEmbeddedNoteCard(block, currentNotePath, links, modifier)
        is MdBlock.EmbeddedFile -> ReaderEmbeddedFileCard(block, modifier)
        MdBlock.HorizontalRule -> ReaderHorizontalRule(modifier)
    }
}

/** H1–H6 忠实渲染（不跳过首个 H1 —— Phase 1 修正 #2） */
@Composable
private fun ReaderHeading(
    block: MdBlock.Heading,
    links: ReaderLinkHandler,
    deadLinks: Set<String>,
    modifier: Modifier = Modifier,
) {
    val (style, top, bottom) = when (block.level) {
        1 -> Triple(AppTypography.mdH1, 30.dp, 12.dp)
        2 -> Triple(AppTypography.titleMedium, 30.dp, 12.dp)
        3 -> Triple(AppTypography.mdH3, 24.dp, 10.dp)
        4 -> Triple(AppTypography.mdH4, 20.dp, 8.dp)
        5 -> Triple(AppTypography.mdH5, 18.dp, 6.dp)
        else -> Triple(AppTypography.mdH6, 16.dp, 6.dp)
    }
    InlineText(
        inlines = block.inlines,
        links = links,
        deadLinks = deadLinks,
        style = style,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = top, bottom = bottom),
    )
}

/** 列表（bullet / ordered）：左缩进 22，间距 5；嵌套子块按层级再缩进（§43）。 */
@Composable
private fun ReaderList(
    items: List<MdListItem>,
    marker: @Composable (Int) -> String?,
    links: ReaderLinkHandler,
    currentNotePath: String,
    deadLinks: Set<String>,
    onOpenImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = marker(index) ?: "•",
                    style = AppTypography.bodyReader,
                    color = if (marker(index) == null) AppColors.textPrimary else AppColors.textTertiary,
                )
                Column(Modifier.weight(1f)) {
                    InlineText(
                        inlines = item.inlines,
                        links = links,
                        deadLinks = deadLinks,
                    )
                    item.children.forEach { child ->
                        ReaderBlockRenderer(
                            block = child,
                            links = links,
                            currentNotePath = currentNotePath,
                            deadLinks = deadLinks,
                            onOpenImage = onOpenImage,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 任务列表（原型 .tasks：18dp 勾选框，完成态删除线 + muted；V1 只读，§38） */
@Composable
private fun ReaderTaskList(
    block: MdBlock.TaskList,
    links: ReaderLinkHandler,
    currentNotePath: String,
    deadLinks: Set<String>,
    onOpenImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        block.items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 5.dp)
                        .size(18.dp)
                        .clip(AppShapes.checkbox)
                        .background(if (item.done) AppColors.textPrimary else Color.Transparent)
                        .border(1.5.dp, if (item.done) AppColors.textPrimary else AppColors.textMeta, AppShapes.checkbox),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.done) {
                        Icon(AppIcons.Check, contentDescription = "已完成", tint = AppColors.surface, modifier = Modifier.size(13.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    InlineText(
                        inlines = item.inlines,
                        links = links,
                        deadLinks = deadLinks,
                        strikethrough = item.done,
                        dimmed = item.done,
                    )
                    item.children.forEach { child ->
                        ReaderBlockRenderer(
                            block = child,
                            links = links,
                            currentNotePath = currentNotePath,
                            deadLinks = deadLinks,
                            onOpenImage = onOpenImage,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 引用块（原型 .md-q）：children 为完整块，嵌套引用自然缩进（§44）。 */
@Composable
private fun ReaderQuote(
    block: MdBlock.Quote,
    links: ReaderLinkHandler,
    currentNotePath: String,
    deadLinks: Set<String>,
    onOpenImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(AppColors.quoteBar),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
        ) {
            block.children.forEach { child ->
                ReaderBlockRenderer(child, links, currentNotePath, deadLinks = deadLinks, onOpenImage = onOpenImage)
            }
        }
    }
}

/* ── Callout（§20-§24，原型 .callout/.co-note/.co-warn）────────── */

/** Callout 档位色（Phase 2 预计算 token，§63 视觉冻结）。 */
private data class CalloutSkin(val background: Color, val border: Color, val accent: Color)

private fun calloutSkin(kind: MdBlock.Callout.Kind): CalloutSkin = when (kind) {
    MdBlock.Callout.Kind.INFO -> CalloutSkin(AppColors.calloutNoteBg, AppColors.calloutNoteBorder, AppColors.calloutNoteTitle)
    MdBlock.Callout.Kind.WARNING -> CalloutSkin(AppColors.calloutWarnBg, AppColors.calloutWarnBorder, AppColors.calloutWarnTitle)
    MdBlock.Callout.Kind.DANGER -> CalloutSkin(AppColors.calloutDangerBg, AppColors.calloutDangerBorder, AppColors.calloutDangerTitle)
    MdBlock.Callout.Kind.SUCCESS -> CalloutSkin(AppColors.calloutSuccessBg, AppColors.calloutSuccessBorder, AppColors.calloutSuccessTitle)
}

@Composable
private fun ReaderCallout(
    block: MdBlock.Callout,
    links: ReaderLinkHandler,
    currentNotePath: String,
    deadLinks: Set<String>,
    onOpenImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = calloutSkin(block.kind)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(AppShapes.small)
            .background(skin.background)
            .border(1.dp, skin.border, AppShapes.small)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // 头部：类型标签（默认原始 TYPE 文本，§22）+ 自定义标题
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                calloutIcon(block.kind),
                contentDescription = null,
                tint = skin.accent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                (block.title ?: block.type.uppercase()),
                style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = skin.accent,
            )
        }
        if (block.blocks.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.blocks.forEach { child ->
                    ReaderBlockRenderer(
                        block = child,
                        links = links,
                        currentNotePath = currentNotePath,
                        deadLinks = deadLinks,
                        onOpenImage = onOpenImage,
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}

private fun calloutIcon(kind: MdBlock.Callout.Kind) = when (kind) {
    MdBlock.Callout.Kind.INFO -> AppIcons.Info
    MdBlock.Callout.Kind.WARNING -> AppIcons.Alert
    MdBlock.Callout.Kind.DANGER -> AppIcons.Alert
    MdBlock.Callout.Kind.SUCCESS -> AppIcons.Check
}

/** 代码块（原型 .code：深色反白 + 语言标签 + 横向滚动；不做语法高亮，§41） */
@Composable
private fun ReaderCodeBlock(block: MdBlock.CodeBlock, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .clip(AppShapes.medium)
            .background(AppColors.codeBlockBg)
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = block.code,
                style = AppTypography.codeBlock,
                color = AppColors.codeBlockText,
            )
        }
        if (block.language != null) {
            Text(
                text = block.language,
                style = AppTypography.badgeText,
                color = AppColors.codeLangLabel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 12.dp),
            )
        }
    }
}

/** 表格（原型 .tbl：横向滚动、表头暖白底、min-width 440dp 触发横滚；单元格支持内联样式） */
@Composable
private fun ReaderTable(block: MdBlock.Table, links: ReaderLinkHandler, deadLinks: Set<String>, modifier: Modifier = Modifier) {
    val columnWidths = remember(block) { estimateColumnWidths(block) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .clip(AppShapes.small)
            .border(1.dp, AppColors.border, AppShapes.small)
            .horizontalScroll(rememberScrollState()),
    ) {
        Column {
            Row(Modifier.background(AppColors.surface)) {
                block.headers.forEachIndexed { i, header ->
                    InlineText(
                        inlines = header,
                        links = links,
                        deadLinks = deadLinks,
                        style = AppTypography.tableText.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .width(columnWidths[i])
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            block.rows.forEach { row ->
                Column {
                    Box(
                        Modifier
                            .width(columnWidths.fold(0.dp) { acc, w -> acc + w })
                            .height(1.dp)
                            .background(AppColors.border),
                    )
                    Row {
                        row.forEachIndexed { i, cell ->
                            InlineText(
                                inlines = cell,
                                links = links,
                                deadLinks = deadLinks,
                                style = AppTypography.tableText,
                                modifier = Modifier
                                    .width(columnWidths[i])
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 每列宽度 = 该列最长单元格估算宽（CJK≈14dp / 其他≈8dp @14sp）+ padding，整体不小于 440dp */
private fun estimateColumnWidths(block: MdBlock.Table): List<Dp> {
    fun estimate(text: String): Int {
        var w = 0
        for (c in text) w += if (c.code > 0x2E7F) 14 else 8
        return w + 28
    }
    val widths = block.headers.indices.map { i ->
        val maxCell = (listOf(block.headers[i].plainText()) + block.rows.mapNotNull { it.getOrNull(i)?.plainText() })
            .maxOfOrNull { estimate(it) } ?: 56
        maxCell.coerceAtLeast(56)
    }
    val total = widths.sum()
    return if (total < 440) {
        widths.map { it * 440 / total }
    } else {
        widths
    }.map { it.dp }
}

/* ── 图片（§25-§31）───────────────────────────────────────── */

/**
 * Reader 内嵌图 UI 态：字节在加载协程内完成降采样解码后，UI 只持有 Bitmap ——
 * 原始 ByteArray 立即可回收（多图笔记不再每张都常驻全尺寸字节）。
 */
private sealed interface ReaderImageUi {
    data object Loading : ReaderImageUi
    data class Ready(val bitmap: androidx.compose.ui.graphics.ImageBitmap, val path: String) : ReaderImageUi
    data object DecodeFailed : ReaderImageUi
    data object Missing : ReaderImageUi
    data object OfflineNotCached : ReaderImageUi
    data object Ambiguous : ReaderImageUi
    data object Error : ReaderImageUi
}

private suspend fun ImageResult.toReaderImageUi(): ReaderImageUi = when (this) {
    is ImageResult.Ready -> {
        val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.obsidiancompanion.util.decodeDownsampled(bytes, com.obsidiancompanion.util.READER_IMAGE_MAX_DIM)
        }
        if (bitmap != null) ReaderImageUi.Ready(bitmap.asImageBitmap(), path) else ReaderImageUi.DecodeFailed
    }
    ImageResult.Missing -> ReaderImageUi.Missing
    ImageResult.OfflineNotCached -> ReaderImageUi.OfflineNotCached
    ImageResult.Ambiguous -> ReaderImageUi.Ambiguous
    is ImageResult.Error -> ReaderImageUi.Error
}

/** Obsidian 图片嵌入 ![[a.png|650]]：Tree 解析 → SHA 缓存 → 降采样解码渲染；点击进查看器看大图。 */
@Composable
fun ReaderImageEmbed(
    block: MdBlock.ImageEmbed,
    currentNotePath: String,
    onOpenImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by produceState<ReaderImageUi>(ReaderImageUi.Loading, block.target, currentNotePath) {
        value = AppGraph.imageRepository.loadImage(block.target, currentNotePath).toReaderImageUi()
    }
    ReaderImageSlot(
        state = state,
        fileName = block.target.substringAfterLast('/'),
        widthPx = block.widthPx,
        onOpenImage = onOpenImage,
        modifier = modifier,
    )
}

/** Markdown 原生图片 ![alt](url)（§31）：Vault 内相对路径走附件管线；外部 URL 保留占位。 */
@Composable
fun ReaderNativeImage(
    block: MdBlock.Image,
    currentNotePath: String,
    onOpenImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isExternal = block.url.startsWith("http://") || block.url.startsWith("https://")
    val state by produceState<ReaderImageUi>(ReaderImageUi.Loading, block.url, currentNotePath) {
        value = if (isExternal) {
            ReaderImageUi.Missing
        } else {
            AppGraph.imageRepository.loadNativeImage(block.url, currentNotePath).toReaderImageUi()
        }
    }
    ReaderImageSlot(
        state = state,
        fileName = block.url.substringAfterLast('/'),
        widthPx = null,
        alt = block.alt,
        externalUrl = if (isExternal) block.url else null,
        onOpenImage = onOpenImage,
        modifier = modifier,
    )
}

/** 图片槽位状态 + 加载中（§29/§56）。Obsidian `|650` 尺寸后缀按 px 上限约束（§30）；解码失败可见回退。 */
@Composable
private fun ReaderImageSlot(
    state: ReaderImageUi,
    fileName: String,
    widthPx: Int?,
    alt: String? = null,
    externalUrl: String? = null,
    onOpenImage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        val maxWidth = this.maxWidth
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
            is ReaderImageUi.Ready -> {
                val targetWidth: Dp? = widthPx?.let { px -> px.dp.coerceAtMost(maxWidth) }
                androidx.compose.foundation.Image(
                    bitmap = state.bitmap,
                    contentDescription = alt ?: fileName,
                    contentScale = ContentScale.Fit,
                    modifier = (if (targetWidth != null) {
                        Modifier.width(targetWidth)
                    } else {
                        Modifier.fillMaxWidth()
                    }).clickable { onOpenImage(state.path) },
                )
            }
            ReaderImageUi.Loading -> ImagePlaceholderCard(
                fileName = fileName,
                caption = if (externalUrl != null) "外部图片 · V1 不加载" else null,
            )
            ReaderImageUi.DecodeFailed -> ImagePlaceholderCard(
                fileName = fileName,
                caption = "图片无法显示",
            )
            ReaderImageUi.Missing -> ImagePlaceholderCard(
                fileName = fileName,
                caption = externalUrl?.let { "外部图片 · V1 不加载" } ?: "Vault 中没有这个附件",
            )
            ReaderImageUi.OfflineNotCached -> ImagePlaceholderCard(
                fileName = fileName,
                caption = "图片尚未缓存\n联网后可显示",
            )
            ReaderImageUi.Ambiguous -> ImagePlaceholderCard(
                fileName = fileName,
                caption = "同名附件有多份，暂无法确定",
            )
            ReaderImageUi.Error -> ImagePlaceholderCard(
                fileName = fileName,
                caption = "图片加载失败",
            )
        }
        }
    }
}

/** 图片占位卡（Phase 2 ImagePlaceholder 风格，§29：显示文件名，不出现破图 icon）。 */
@Composable
private fun ImagePlaceholderCard(fileName: String, caption: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .border(1.dp, AppColors.borderStrong, AppShapes.medium)
            .background(AppColors.surfaceWarm)
            .padding(vertical = 22.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AppIcons.Image, contentDescription = null, tint = AppColors.textMeta, modifier = Modifier.size(24.dp))
        Text(
            fileName.ifEmpty { "图片" },
            style = AppTypography.caption.copy(fontFamily = FontFamily.Monospace),
            color = AppColors.textMeta,
        )
        if (caption != null) {
            Text(
                caption,
                style = AppTypography.caption,
                color = AppColors.textTertiary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/* ── 嵌入卡（§32-§34，原型 .embed-card）───────────────────────── */

/** 笔记嵌入 ![[Note#H]]：V1 不展开正文，渲染引用卡片（原型 .embed-card）。 */
@Composable
fun ReaderEmbeddedNoteCard(
    block: MdBlock.EmbeddedNote,
    currentNotePath: String,
    links: ReaderLinkHandler,
    modifier: Modifier = Modifier,
) {
    val repoId by produceState<String?>(initialValue = null, currentNotePath) {
        value = AppGraph.settings.flow.firstOrNull()?.repoId
    }
    val resolution by produceState<LinkResolution?>(initialValue = null, block.target, block.heading, repoId) {
        val id = repoId ?: return@produceState
        value = AppGraph.linkResolver.resolveNote(id, block.target, block.heading, currentNotePath)
    }
    val clickable = resolution is LinkResolution.Note || resolution is LinkResolution.SameNote

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(AppShapes.small)
            .background(AppColors.surface)
            .border(1.dp, AppColors.border, AppShapes.small)
            .then(if (clickable) Modifier.clickable { links.onWikiLink(embedWikiLink(block)) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(AppShapes.small)
                .background(AppColors.surfaceWarm),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.FileText,
                contentDescription = null,
                tint = AppColors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                cardTitle(block, resolution),
                style = AppTypography.titleSmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (resolution is LinkResolution.NotFound || resolution is LinkResolution.Ambiguous) {
                    AppColors.textMeta
                } else {
                    AppColors.textPrimary
                },
            )
            Text(
                cardSubtitle(block, resolution),
                style = AppTypography.caption,
                color = AppColors.textTertiary,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = AppColors.textTertiary, modifier = Modifier.size(16.dp))
    }
}

private fun embedWikiLink(block: MdBlock.EmbeddedNote): MdInline.WikiLink =
    MdInline.WikiLink(block.target, block.heading, alias = null, embed = true, raw = "![[${block.target}]]")

private fun cardTitle(block: MdBlock.EmbeddedNote, resolution: LinkResolution?): String {
    val note = resolution as? LinkResolution.Note
    if (note != null) return note.path.substringAfterLast('/').removeSuffix(".md")
    return when (resolution) {
        is LinkResolution.Ambiguous -> block.target + "（同名多份）"
        is LinkResolution.NotFound -> block.target + "（没有找到）"
        else -> block.target.ifEmpty { "本笔记" }
    }
}

private fun cardSubtitle(block: MdBlock.EmbeddedNote, resolution: LinkResolution?): String {
    val note = resolution as? LinkResolution.Note
    return when {
        note != null && block.heading != null -> "引用笔记 · ${note.path} › ${block.heading}"
        note != null -> "引用笔记 · ${note.path}"
        resolution is LinkResolution.SameNote -> block.heading?.let { "引用笔记 · 本笔记 › $it" } ?: "本笔记锚点"
        else -> "Vault 内没有解析到目标"
    }
}

/** 未知类型嵌入（.canvas / 音频等，§34/§68）：占位卡，不 crash。 */
@Composable
fun ReaderEmbeddedFileCard(block: MdBlock.EmbeddedFile, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(AppShapes.small)
            .background(AppColors.surface)
            .border(1.dp, AppColors.border, AppShapes.small)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(AppShapes.small)
                .background(AppColors.surfaceWarm),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.FileText, contentDescription = null, tint = AppColors.textSecondary, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                block.label ?: block.target.substringAfterLast('/'),
                style = AppTypography.titleSmall,
            )
            Text(
                "${block.target.substringAfterLast('/')} · V1 暂不支持此类型嵌入",
                style = AppTypography.caption,
                color = AppColors.textTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 水平分隔线（原型 .md-hr） */
@Composable
private fun ReaderHorizontalRule(modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .height(1.dp)
            .background(AppColors.borderStrong),
    )
}

/* ── 内联文本：AnnotatedString + 点击区域 ───────────────────── */

/** 递归携带的字符样式状态 */
private data class SpanCarrier(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val color: Color? = null,
    val underline: Boolean = false,
)

@Composable
fun InlineText(
    inlines: List<MdInline>,
    links: ReaderLinkHandler,
    modifier: Modifier = Modifier,
    deadLinks: Set<String> = emptySet(),
    style: androidx.compose.ui.text.TextStyle = AppTypography.bodyReader,
    strikethrough: Boolean = false,
    dimmed: Boolean = false,
) {
    val baseColor = if (dimmed) AppColors.textMeta else AppColors.textPrimary
    val tapRanges = remember(inlines) { mutableListOf<TapRange>() }
    val annotated = remember(inlines, strikethrough, dimmed, deadLinks) {
        buildAnnotatedString {
            fun emit(list: List<MdInline>, carrier: SpanCarrier) {
                list.forEach { inline ->
                    when (inline) {
                        is MdInline.Text -> {
                            append(inline.value)
                            val s = SpanStyle(
                                color = carrier.color ?: baseColor,
                                fontWeight = if (carrier.bold) FontWeight.Bold else null,
                                fontStyle = if (carrier.italic) FontStyle.Italic else null,
                                textDecoration = when {
                                    carrier.strike || strikethrough -> TextDecoration.LineThrough
                                    carrier.underline -> TextDecoration.Underline
                                    else -> null
                                },
                            )
                            addStyle(s, length - inline.value.length, length)
                        }
                        is MdInline.Code -> {
                            append(inline.text)
                            addStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.5.sp,
                                    background = AppColors.surfaceWarm,
                                    color = carrier.color ?: baseColor,
                                ),
                                length - inline.text.length,
                                length,
                            )
                        }
                        is MdInline.Bold -> emit(inline.children, carrier.copy(bold = true))
                        is MdInline.Italic -> emit(inline.children, carrier.copy(italic = true))
                        is MdInline.Strike -> emit(inline.children, carrier.copy(strike = true))
                        is MdInline.Link -> {
                            val start = length
                            emit(inline.children, carrier.copy(underline = true))
                            tapRanges.add(TapRange(start, length, inline.url, null))
                        }
                        is MdInline.WikiLink -> {
                            val start = length
                            append(inline.displayText())
                            // §15：死链 muted 色、正常链接 accent；点击行为一致（Snackbar 说明）
                            val linkColor = if (inline.raw in deadLinks) AppColors.textMeta else AppColors.accent
                            addStyle(
                                SpanStyle(color = carrier.color ?: linkColor),
                                start,
                                length,
                            )
                            tapRanges.add(TapRange(start, length, null, inline))
                        }
                        is MdInline.Image -> {
                            // 行内原生图片：V1 文本位占位（块级图片才有真渲染）
                            val label = "[图片:${inline.url.substringAfterLast('/')}]"
                            append(label)
                            addStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.5.sp,
                                    color = AppColors.textMeta,
                                    background = AppColors.surfaceWarm,
                                ),
                                length - label.length,
                                length,
                            )
                        }
                        MdInline.SoftBreak -> append(" ")
                        MdInline.HardBreak -> append("\n")
                    }
                }
            }
            emit(inlines, SpanCarrier())
        }
    }
    val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = style,
        modifier = modifier.pointerInput(inlines) {
            detectTapGestures { offset ->
                val layout = layoutResult.value ?: return@detectTapGestures
                val position = layout.getOffsetForPosition(offset)
                val hit = tapRanges.lastOrNull { position >= it.start && position < it.end }
                when {
                    hit?.url != null -> links.onExternalLink(hit.url)
                    hit?.wiki != null -> links.onWikiLink(hit.wiki)
                }
            }
        },
        onTextLayout = { layoutResult.value = it },
    )
}
