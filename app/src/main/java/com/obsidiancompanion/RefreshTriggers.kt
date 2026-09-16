package com.obsidiancompanion

import com.obsidiancompanion.data.network.NetworkMonitor
import com.obsidiancompanion.data.repository.RepositoryIndexRepository
import com.obsidiancompanion.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 6B：自动刷新触发器。只决定「何时」调用唯一的 refreshTree(force=false)，
 * 不存在第二套刷新实现；手动刷新（force=true）不经过这里。
 *
 * 三个自动入口，全部受 settings.autoRefresh 门控（§10）：
 * - startup：HomeViewModel.init（既有逻辑，保持不动）
 * - foreground：MainActivity.onStart → onForeground()，距上次成功刷新 ≥3 分钟才尝试（§8）
 * - network recovery：Offline → Online 边沿触发一次（§9）；抖动期间的真实去重由
 *   refreshTree 内的 freshness window 兜底，HTTP 结果仍是唯一真相。
 */
class RefreshTriggers(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    private val index: RepositoryIndexRepository,
) {

    private val started = AtomicBoolean(false)

    /** App 启动时调用一次（幂等）：开始监听网络恢复。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            network.isOnlineFlow.toNetworkRecoveryTriggers().collect { auto() }
        }
    }

    /** 回到前台（MainActivity.onStart，冷启动也会走一次；freshness window 保证不重复请求）。 */
    fun onForeground() {
        scope.launch { auto(minIntervalMs = FOREGROUND_MIN_INTERVAL_MS) }
    }

    private suspend fun auto(minIntervalMs: Long = 0L) {
        val s = settings.flow.first()
        if (!autoRefreshAllowed(s.autoRefresh, s.repoId != null, network.isOnline)) return
        if (minIntervalMs > 0 && !index.elapsedSinceSuccessAtLeast(minIntervalMs)) return
        index.refreshTree()
    }

    companion object {
        /** spec §8：2~5 分钟以上才尝试，不每次 Resume 都请求。 */
        const val FOREGROUND_MIN_INTERVAL_MS = 3L * 60_000
    }
}

/**
 * §10 自动刷新门控（纯函数，JVM 可测）：关闭自动刷新 / 未配置仓库 / 离线时不触发。
 * 全部自动入口（startup = HomeViewModel.init、foreground、network recovery）共用同一判定；
 * 手动刷新（force=true）不经过这里。
 */
fun autoRefreshAllowed(autoRefresh: Boolean, hasRepo: Boolean, isOnline: Boolean): Boolean =
    autoRefresh && hasRepo && isOnline

/**
 * Offline → Online 边沿检测（纯函数，JVM 可测）：
 * 每次从 false 变 true 恰好发一个触发；初始在线/初始离线都不触发；快速抖动会产生
 * 多个边沿，但每次都走 refreshTree(force=false)，freshness window 内只有第一次真正请求。
 */
fun Flow<Boolean>.toNetworkRecoveryTriggers(): Flow<Unit> = flow {
    var prev: Boolean? = null
    collect { online ->
        val recovered = online && prev == false
        prev = online
        if (recovered) emit(Unit)
    }
}
