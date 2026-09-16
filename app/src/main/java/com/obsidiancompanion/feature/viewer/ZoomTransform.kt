package com.obsidiancompanion.feature.viewer

/**
 * 图片查看器变换数学（纯 Kotlin，JVM 可测）。
 *
 * 模型：scale 围绕容器中心应用（Compose graphicsLayer 默认 TransformOrigin.Center），
 * offset 为 px 平移。内容按 ContentScale.Fit 落入容器，displayed = fitScale(content) * scale。
 */
data class ViewTransform(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f)

/** 容器与内容的像素尺寸；content 未知（未解码）时传 0，钳制会回中。 */
data class Viewport(val containerW: Float, val containerH: Float, val contentW: Float, val contentH: Float) {
    val centerX: Float get() = containerW / 2f
    val centerY: Float get() = containerH / 2f
}

object ZoomTransform {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 6f
    const val DOUBLE_TAP_SCALE = 3f

    /** ContentScale.Fit 的基础缩放系数；任一尺寸未知按 1:1。 */
    fun fitScale(vp: Viewport): Float {
        if (unknown(vp.containerW) || unknown(vp.containerH) || unknown(vp.contentW) || unknown(vp.contentH)) return 1f
        return minOf(vp.containerW / vp.contentW, vp.containerH / vp.contentH)
    }

    /**
     * 应用一次手势增量：zoom 为倍率（围绕 centroid），pan 为 px 位移（均为容器坐标系）。
     * 保持 centroid 下的内容点不动，结果已钳制。
     */
    fun transform(
        current: ViewTransform,
        zoom: Float,
        panX: Float,
        panY: Float,
        centroidX: Float,
        centroidY: Float,
        vp: Viewport,
    ): ViewTransform {
        val newScale = (current.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = if (current.scale > 0f) newScale / current.scale else 1f
        val ox = centroidX - vp.centerX - (centroidX - vp.centerX - current.offsetX) * ratio + panX
        val oy = centroidY - vp.centerY - (centroidY - vp.centerY - current.offsetY) * ratio + panY
        return clamped(ViewTransform(newScale, ox, oy), vp)
    }

    /** 双击：放大状态 → 还原 1x 回中；1x → 围绕双击点放大到 DOUBLE_TAP_SCALE。 */
    fun doubleTap(current: ViewTransform, tapX: Float, tapY: Float, vp: Viewport): ViewTransform =
        if (current.scale > MIN_SCALE) {
            clamped(ViewTransform(), vp)
        } else {
            transform(current, DOUBLE_TAP_SCALE / current.scale, 0f, 0f, tapX, tapY, vp)
        }

    /**
     * 钳制：scale 限 [MIN, MAX]；每轴可平移范围 = 超出容器的一半（未超出则回中 0）。
     * 内容尺寸未知时不允许平移（避免解码前乱拖）。
     */
    fun clamped(t: ViewTransform, vp: Viewport): ViewTransform {
        val scale = t.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (unknown(vp.contentW) || unknown(vp.contentH)) return ViewTransform(scale, 0f, 0f)
        val fit = fitScale(vp)
        val maxX = ((vp.contentW * fit * scale - vp.containerW) / 2f).coerceAtLeast(0f)
        val maxY = ((vp.contentH * fit * scale - vp.containerH) / 2f).coerceAtLeast(0f)
        return ViewTransform(scale, t.offsetX.coerceIn(-maxX, maxX), t.offsetY.coerceIn(-maxY, maxY))
    }

    /** 尺寸不可用：0（未布局/未解码）或 NaN（painter 无 intrinsic 尺寸，如 Size.Unspecified）。 */
    private fun unknown(v: Float): Boolean = v.isNaN() || v <= 0f
}
