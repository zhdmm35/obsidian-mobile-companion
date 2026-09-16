package com.obsidiancompanion

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 6B §9：Offline → Online 边沿检测 —— 一次网络恢复恰好触发一次。 */
class NetworkRecoveryTriggersTest {

    @Test
    fun `初始在线 不触发`() = runTest {
        assertEquals(0, flowOf(true).toNetworkRecoveryTriggers().toList().size)
    }

    @Test
    fun `初始离线 不触发`() = runTest {
        assertEquals(0, flowOf(false).toNetworkRecoveryTriggers().toList().size)
    }

    @Test
    fun `离线到在线 恰好触发一次`() = runTest {
        val triggers = flowOf(false, true).toNetworkRecoveryTriggers().toList()
        assertEquals(1, triggers.size)
    }

    @Test
    fun `持续在线 只有一次边沿`() = runTest {
        assertEquals(1, flowOf(false, true, true, true).toNetworkRecoveryTriggers().toList().size)
    }

    @Test
    fun `反复抖动 每次恢复各触发一次`() = runTest {
        // false→true→false→true：两次恢复。抖动导致的频率限制由 refreshTree 的
        // freshness window 兜底（30s 内只有第一次真正请求 HTTP），这里只测边沿语义。
        assertEquals(2, flowOf(false, true, false, true).toNetworkRecoveryTriggers().toList().size)
    }

    @Test
    fun `一直离线 不触发`() = runTest {
        assertEquals(0, flowOf(false, false, false).toNetworkRecoveryTriggers().toList().size)
    }
}
