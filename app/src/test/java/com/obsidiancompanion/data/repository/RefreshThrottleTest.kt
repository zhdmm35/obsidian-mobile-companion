package com.obsidiancompanion.data.repository

import com.obsidiancompanion.RefreshTriggers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6B §4：freshness window 去重语义（纯逻辑，假时钟）。 */
class RefreshThrottleTest {

    @Test
    fun `从未成功刷新过 不跳过`() {
        val t = RefreshThrottle(now = { 1_000L })
        assertFalse(t.shouldSkipAutoAt(1_000L))
    }

    @Test
    fun `刚成功刷新过 window 内跳过`() {
        var now = 1_000_000L
        val t = RefreshThrottle(freshnessWindowMs = 30_000, now = { now })
        t.markSuccessAt(now)
        now += 29_999
        assertTrue(t.shouldSkipAutoAt(now))
    }

    @Test
    fun `恰好到达 window 边界 不再跳过`() {
        var now = 1_000_000L
        val t = RefreshThrottle(freshnessWindowMs = 30_000, now = { now })
        t.markSuccessAt(now)
        now += 30_000
        assertFalse(t.shouldSkipAutoAt(now))
    }

    @Test
    fun `默认 window 为 30 秒`() {
        assertEquals(30_000L, RefreshThrottle.DEFAULT_FRESHNESS_WINDOW_MS)
    }

    @Test
    fun `前台刷新最小间隔为 3 分钟`() {
        assertEquals(3L * 60_000, RefreshTriggers.FOREGROUND_MIN_INTERVAL_MS)
    }
}
