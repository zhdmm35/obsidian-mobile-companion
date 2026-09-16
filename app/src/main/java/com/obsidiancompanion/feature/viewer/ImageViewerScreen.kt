package com.obsidiancompanion.feature.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.data.repository.ImageResult
import com.obsidiancompanion.feature.sync.SpinningIcon
import kotlinx.coroutines.flow.firstOrNull

/** 查看器为深色全屏（图片阅读对比度优先），不套用羊皮纸浅色主题。 */
private val ViewerBackground = Color(0xFF121110)
private val ViewerTextPrimary = Color(0xFFF2F0EB)
private val ViewerTextSecondary = Color(0xFFA6A29A)

/** 解码上限：大图按最长边 ~4096px 采样，兼顾缩放细节与内存（硬件 bitmap 不占 Java 堆）。 */
private const val MAX_DECODE_PX = 4096

/**
 * 图片查看器：Files 页点开 / Reader 图片放大的落地屏。
 * 同目录图片 HorizontalPager 滑动切换；双指缩放、双击放大/还原、放大后拖动平移。
 * 加载走 ImageRepository.loadByPath（Tree → SHA 缓存 → GitHub），离线未缓存有明确占位。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(initialPath: String, onBack: () -> Unit) {
    // 同目录图片快照：取一次即可，翻看期间不追 live 更新（避免 Tree 刷新导致页码跳动）
    val pages by produceState<List<String>?>(initialValue = null, initialPath) {
        val repoId = AppGraph.settings.flow.firstOrNull()?.repoId
        value = if (repoId == null) {
            listOf(initialPath)
        } else {
            siblingImagePaths(AppGraph.database.repoEntryDao().getAll(repoId), initialPath)
        }
    }

    Column(Modifier.fillMaxSize().background(ViewerBackground)) {
        val list = pages
        if (list == null) {
            ViewerTopBar(title = initialPath.substringAfterLast('/'), counter = null, onBack = onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SpinningIcon(AppIcons.Refresh, tint = ViewerTextSecondary, size = 22.dp, contentDescription = null)
            }
            return@Column
        }

        val pagerState = rememberPagerState(initialPage = list.indexOf(initialPath).coerceAtLeast(0)) { list.size }
        // 当前页缩放系数：>1 时禁用翻页（先拖图），回到 1x 才允许滑动换页
        var currentScale by remember { mutableStateOf(1f) }
        LaunchedEffect(pagerState.currentPage) { currentScale = 1f }

        ViewerTopBar(
            title = list[pagerState.currentPage].substringAfterLast('/'),
            counter = if (list.size > 1) "${pagerState.currentPage + 1} / ${list.size}" else null,
            onBack = onBack,
        )
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = currentScale <= 1f,
            beyondViewportPageCount = 1, // 预载相邻一页，快速滑动不闪加载圈
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ViewerPage(
                path = list[page],
                onScaleChange = { if (page == pagerState.currentPage) currentScale = it },
            )
        }
    }
}

@Composable
private fun ViewerTopBar(title: String, counter: String?, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(AppIcons.Back, contentDescription = "返回", tint = ViewerTextPrimary)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = AppTypography.bodySmall,
                color = ViewerTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (counter != null) {
                Text(counter, style = AppTypography.caption, color = ViewerTextSecondary)
            }
        }
        // 与返回键等宽占位，标题保持居中
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun ViewerPage(path: String, onScaleChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val result by produceState<ImageResult?>(initialValue = null, path) {
        value = AppGraph.imageRepository.loadByPath(path)
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val r = result) {
            null -> SpinningIcon(AppIcons.Refresh, tint = ViewerTextSecondary, size = 22.dp, contentDescription = null)
            is ImageResult.Ready -> ZoomableImage(result = r, onScaleChange = onScaleChange)
            is ImageResult.Missing -> ViewerPlaceholder(path, "Vault 中没有这个文件")
            is ImageResult.OfflineNotCached -> ViewerPlaceholder(path, "图片尚未缓存\n联网后可显示")
            is ImageResult.Ambiguous -> ViewerPlaceholder(path, "同名附件有多份，暂无法确定")
            is ImageResult.Error -> ViewerPlaceholder(path, "图片加载失败")
        }
    }
}

@Composable
private fun ZoomableImage(result: ImageResult.Ready, onScaleChange: (Float) -> Unit) {
    val context = LocalContext.current
    var transform by remember { mutableStateOf(ViewTransform()) }
    var decodeFailed by remember(result) { mutableStateOf(false) }
    // 容器与解码后内容尺寸（px），用于缩放钳制
    var viewport by remember(result) { mutableStateOf(Viewport(0f, 0f, 0f, 0f)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = viewport.copy(containerW = it.width.toFloat(), containerH = it.height.toFloat()) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        transform = ZoomTransform.doubleTap(transform, tap.x, tap.y, viewport)
                        onScaleChange(transform.scale)
                    },
                )
            }
            .pointerInput(Unit) {
                detectPanZoomGestures(scale = { transform.scale }) { centroid, pan, zoom ->
                    transform = ZoomTransform.transform(transform, zoom, pan.x, pan.y, centroid.x, centroid.y, viewport)
                    onScaleChange(transform.scale)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (decodeFailed) {
            ViewerPlaceholder(result.path, "图片无法显示（格式可能暂不支持）")
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(result.bytes)
                    .size(MAX_DECODE_PX, MAX_DECODE_PX)
                    .build(),
                contentDescription = result.path.substringAfterLast('/'),
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val intrinsic = state.painter.intrinsicSize
                    viewport = viewport.copy(contentW = intrinsic.width, contentH = intrinsic.height)
                },
                onError = { decodeFailed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.offsetX
                        translationY = transform.offsetY
                    },
            )
        }
    }
}

/**
 * 缩放/平移手势（与 Pager 共存的关键）：
 * - 双指（捏合）或已放大（scale>1）时才消费位移 → 单指且 1x 时事件留给 Pager 翻页
 * - detectTransformGestures 会消费一切拖动，无法与 Pager 共存，故自定义
 */
private suspend fun PointerInputScope.detectPanZoomGestures(
    scale: () -> Float,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount == 0) break
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val engaged = pressedCount >= 2 || scale() > 1f
            if (engaged && (zoom != 1f || pan != Offset.Zero)) {
                onTransform(event.calculateCentroid(), pan, zoom)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    }
}

/** 深色占位（对齐 Reader 占位语义：显示文件名，不出现破图 icon）。 */
@Composable
private fun ViewerPlaceholder(path: String, caption: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        Icon(AppIcons.Image, contentDescription = null, tint = ViewerTextSecondary, modifier = Modifier.size(28.dp))
        Text(
            path.substringAfterLast('/').ifEmpty { "图片" },
            style = AppTypography.caption,
            color = ViewerTextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(caption, style = AppTypography.caption, color = ViewerTextSecondary, textAlign = TextAlign.Center)
    }
}
