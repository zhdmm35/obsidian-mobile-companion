package com.obsidiancompanion.data.repository

/**
 * Phase 6B §4：自动刷新短时间去重（freshness window）。
 * 刚成功刷新过（Success / NotModified 都算 fresh）+ 自动触发 → 跳过，不发 HTTP。
 * 手动刷新（force=true）不经过这里，始终直接请求。
 * 时间显式传入（而非内部取钟），测试可注入假时钟。
 */
class RefreshThrottle(
    private val freshnessWindowMs: Long = DEFAULT_FRESHNESS_WINDOW_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    var lastSuccessAt: Long = 0L
        private set

    @Volatile
    private var hasSuccess = false

    /** 当前时间（可注入：androidTest 用假时钟控制 freshness window 的推进）。 */
    fun nowMs(): Long = now()

    /** 自动刷新在 nowMs 时是否应被去重跳过（有过成功 且 距上次成功 < window）。 */
    fun shouldSkipAutoAt(nowMs: Long = now()): Boolean =
        hasSuccess && nowMs - lastSuccessAt < freshnessWindowMs

    fun markSuccessAt(nowMs: Long = now()) {
        hasSuccess = true
        lastSuccessAt = nowMs
    }

    companion object {
        /** spec §4 建议 15~30 秒。 */
        const val DEFAULT_FRESHNESS_WINDOW_MS = 30_000L
    }
}
