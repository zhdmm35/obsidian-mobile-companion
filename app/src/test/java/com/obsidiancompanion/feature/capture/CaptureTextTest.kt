package com.obsidiancompanion.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 快速收集：默认标题提取 + 目标文件夹合法化。 */
class CaptureTextTest {

    /* ── defaultCaptureTitle ─────────────────────────────── */

    @Test
    fun `title from first non-empty line`() {
        assertEquals("牛奶 鸡蛋", defaultCaptureTitle("\n\n牛奶 鸡蛋\n面包"))
    }

    @Test
    fun `title strips markdown decoration`() {
        assertEquals("周会纪要", defaultCaptureTitle("## 周会纪要\n正文"))
        assertEquals("引用内容", defaultCaptureTitle("> 引用内容"))
        assertEquals("买牛奶", defaultCaptureTitle("- [ ] 买牛奶"))
        assertEquals("买牛奶", defaultCaptureTitle("- [x] 买牛奶"))
        assertEquals("列表项", defaultCaptureTitle("- 列表项"))
    }

    @Test
    fun `title strips inline marks and truncates`() {
        assertEquals("链接文字", defaultCaptureTitle("[链接文字](https://a.b)"))
        val long = "这是一条非常非常非常长的分享标题它一定会超过三十个字符所以应该被截断掉"
        assertEquals(30, defaultCaptureTitle(long).length)
    }

    @Test
    fun `fallback title when nothing usable`() {
        assertEquals("快速收集", defaultCaptureTitle(""))
        assertEquals("快速收集", defaultCaptureTitle("\n\n  \n"))
        assertEquals("快速收集", defaultCaptureTitle("###"))
    }

    @Test
    fun `url as first line kept as title base`() {
        assertEquals("https://example.com/a?b=1", defaultCaptureTitle("https://example.com/a?b=1"))
    }

    /* ── sanitizeCaptureFolder ───────────────────────────── */

    @Test
    fun `blank means vault root`() {
        assertEquals("", sanitizeCaptureFolder(""))
        assertEquals("", sanitizeCaptureFolder("   "))
        assertEquals("", sanitizeCaptureFolder("/"))
    }

    @Test
    fun `single and nested folders normalized`() {
        assertEquals("Inbox", sanitizeCaptureFolder("Inbox"))
        assertEquals("收集/网页", sanitizeCaptureFolder("收集/网页"))
        assertEquals("a/b", sanitizeCaptureFolder("/a/b/"))
    }

    @Test
    fun `dangerous segments rejected`() {
        assertNull(sanitizeCaptureFolder(".."))
        assertNull(sanitizeCaptureFolder("a/../b"))
        assertNull(sanitizeCaptureFolder("a//b"))
        assertNull(sanitizeCaptureFolder(".obsidian/x"))
        assertNull(sanitizeCaptureFolder("a\\b"))
        assertNull(sanitizeCaptureFolder("a:b"))
    }
}
