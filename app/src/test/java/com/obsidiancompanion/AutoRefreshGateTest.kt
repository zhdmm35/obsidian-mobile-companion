package com.obsidiancompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6B §10：自动刷新门控 —— 关闭开关 / 未配置仓库 / 离线时，
 * 全部自动入口（startup / foreground / network recovery）一律不发起；手动刷新不经过门控。
 */
class AutoRefreshGateTest {

    @Test
    fun `开关开启 已配置仓库 在线 允许自动刷新`() {
        assertTrue(autoRefreshAllowed(autoRefresh = true, hasRepo = true, isOnline = true))
    }

    @Test
    fun `关闭自动刷新 不允许`() {
        assertFalse(autoRefreshAllowed(autoRefresh = false, hasRepo = true, isOnline = true))
    }

    @Test
    fun `未配置仓库 不允许`() {
        assertFalse(autoRefreshAllowed(autoRefresh = true, hasRepo = false, isOnline = true))
    }

    @Test
    fun `离线 不允许`() {
        assertFalse(autoRefreshAllowed(autoRefresh = true, hasRepo = true, isOnline = false))
    }
}
