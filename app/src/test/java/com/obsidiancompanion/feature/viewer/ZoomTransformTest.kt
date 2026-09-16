package com.obsidiancompanion.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/** 查看器缩放数学：钳制边界 / 缩放中心不动点 / 双击切换 / 未知内容尺寸回中。 */
class ZoomTransformTest {

    /** 竖屏容器 1000×2000，方形内容 1000×1000（Fit 基础 = 1000×1000）。 */
    private val vp = Viewport(containerW = 1000f, containerH = 2000f, contentW = 1000f, contentH = 1000f)

    @Test
    fun `scale clamped to min and max`() {
        assertEquals(ZoomTransform.MIN_SCALE, ZoomTransform.clamped(ViewTransform(scale = 0.2f), vp).scale)
        assertEquals(ZoomTransform.MAX_SCALE, ZoomTransform.clamped(ViewTransform(scale = 99f), vp).scale)
    }

    @Test
    fun `fit not filling container means no pan range at 1x`() {
        // 1x 下内容 1000×1000 < 容器 1000×2000：两轴都回中
        val t = ZoomTransform.transform(ViewTransform(), zoom = 1f, panX = 300f, panY = 300f, centroidX = 500f, centroidY = 1000f, vp = vp)
        assertEquals(1f, t.scale)
        assertEquals(0f, t.offsetX)
        assertEquals(0f, t.offsetY)
    }

    @Test
    fun `zoom about center keeps zero offset`() {
        val t = ZoomTransform.transform(ViewTransform(), zoom = 2f, panX = 0f, panY = 0f, centroidX = 500f, centroidY = 1000f, vp = vp)
        assertEquals(2f, t.scale)
        assertEquals(0f, t.offsetX, 0.001f)
        assertEquals(0f, t.offsetY, 0.001f)
    }

    @Test
    fun `zoom about off-center centroid keeps that content point stationary`() {
        // 围绕 (250, 1000) 放大 2x：该点下的内容点手势前后屏幕坐标不变
        val before = ViewTransform(scale = 1f)
        val t = ZoomTransform.transform(before, zoom = 2f, panX = 0f, panY = 0f, centroidX = 250f, centroidY = 1000f, vp = vp)
        // 内容点 c = (centroid - center - offset)/scale = (-250, 0)；新屏幕点 = center + c*scale' + offset'
        val screenX = 500f + (-250f) * t.scale + t.offsetX
        val screenY = 1000f + 0f * t.scale + t.offsetY
        assertEquals(250f, screenX, 0.001f)
        assertEquals(1000f, screenY, 0.001f)
    }

    @Test
    fun `pan clamped to overflow half at 2x`() {
        // 2x：displayed 2000×2000；x 溢出 (2000-1000)/2 = 500，y 不溢出（容器高 2000）
        val zoomed = ViewTransform(scale = 2f)
        val t = ZoomTransform.transform(zoomed, zoom = 1f, panX = 9999f, panY = 9999f, centroidX = 500f, centroidY = 1000f, vp = vp)
        assertEquals(500f, t.offsetX)
        assertEquals(0f, t.offsetY)
    }

    @Test
    fun `double tap toggles between 1x and 3x`() {
        val zoomed = ZoomTransform.doubleTap(ViewTransform(), tapX = 250f, tapY = 500f, vp = vp)
        assertEquals(ZoomTransform.DOUBLE_TAP_SCALE, zoomed.scale)
        val restored = ZoomTransform.doubleTap(zoomed, tapX = 250f, tapY = 500f, vp = vp)
        assertEquals(1f, restored.scale)
        assertEquals(0f, restored.offsetX)
        assertEquals(0f, restored.offsetY)
    }

    @Test
    fun `double tap zooms around tap point`() {
        // 点击 (400, 900)（中心附近）：放大 3x 后该点下的内容点仍在指尖下
        val t = ZoomTransform.doubleTap(ViewTransform(), tapX = 400f, tapY = 900f, vp = vp)
        val screenX = 500f + (400f - 500f) * t.scale + t.offsetX
        val screenY = 1000f + (900f - 1000f) * t.scale + t.offsetY
        assertEquals(400f, screenX, 0.001f)
        assertEquals(900f, screenY, 0.001f)
    }

    @Test
    fun `double tap near edge hits pan bounds`() {
        // 点击太靠近顶部时，保持不动点需要的平移超出范围 → 钳制到边缘（不露出空白）
        val t = ZoomTransform.doubleTap(ViewTransform(), tapX = 250f, tapY = 500f, vp = vp)
        assertEquals(ZoomTransform.DOUBLE_TAP_SCALE, t.scale)
        assertEquals(500f, t.offsetY) // maxY = (1000*3 - 2000)/2
    }

    @Test
    fun `unknown content size recenters offsets`() {
        val unknown = Viewport(containerW = 1000f, containerH = 2000f, contentW = 0f, contentH = 0f)
        val t = ZoomTransform.clamped(ViewTransform(scale = 2f, offsetX = 400f, offsetY = 400f), unknown)
        assertEquals(2f, t.scale)
        assertEquals(0f, t.offsetX)
        assertEquals(0f, t.offsetY)
    }

    @Test
    fun `NaN intrinsic size is treated as unknown`() {
        // painter.intrinsicSize 为 Size.Unspecified（宽高 NaN）时等同未知：1:1、offset 回中，不产生 NaN 平移
        val nan = Viewport(containerW = 1000f, containerH = 2000f, contentW = Float.NaN, contentH = Float.NaN)
        assertEquals(1f, ZoomTransform.fitScale(nan))
        val t = ZoomTransform.clamped(ViewTransform(scale = 2f, offsetX = 400f, offsetY = 400f), nan)
        assertEquals(2f, t.scale)
        assertEquals(0f, t.offsetX)
        assertEquals(0f, t.offsetY)
    }

    @Test
    fun `fitScale letterboxes wide content`() {
        val wide = Viewport(containerW = 1000f, containerH = 1000f, contentW = 2000f, contentH = 1000f)
        assertEquals(0.5f, ZoomTransform.fitScale(wide))
        // 宽图 3x：displayed 3000×1500；x 溢出 1000，y 溢出 250
        val t = ZoomTransform.clamped(ViewTransform(scale = 3f, offsetX = 9999f, offsetY = 9999f), wide)
        assertEquals(1000f, t.offsetX)
        assertEquals(250f, t.offsetY)
    }
}
