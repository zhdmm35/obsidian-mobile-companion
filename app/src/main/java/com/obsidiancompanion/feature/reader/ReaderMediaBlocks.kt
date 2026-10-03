package com.obsidiancompanion.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.firstOrNull

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
    // Compose 1.7.6 lint 未识别下方 value 赋值，仅豁免此调用。
    @Suppress("ProduceStateDoesNotAssignValue")
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
    // 外部图是编译期已知的常量占位：直接以 Missing 为初值，不为它启动只为赋常量的协程
    // Compose 1.7.6 lint 未识别下方 value 赋值，仅豁免此调用。
    @Suppress("ProduceStateDoesNotAssignValue")
    val state by produceState<ReaderImageUi>(
        if (isExternal) ReaderImageUi.Missing else ReaderImageUi.Loading,
        block.url, currentNotePath,
    ) {
        if (!isExternal) {
            value = AppGraph.imageRepository.loadNativeImage(block.url, currentNotePath).toReaderImageUi()
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
                caption = null, // 外部图以 Missing 为初值，不会经过 Loading
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
    // Compose 1.7.6 lint 未识别下方 value 赋值，仅豁免此调用。
    @Suppress("ProduceStateDoesNotAssignValue")
    val repoId by produceState<String?>(initialValue = null, currentNotePath) {
        value = AppGraph.settings.flow.firstOrNull()?.repoId
    }
    // Compose 1.7.6 lint 未识别下方 value 赋值，仅豁免此调用。
    @Suppress("ProduceStateDoesNotAssignValue")
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
