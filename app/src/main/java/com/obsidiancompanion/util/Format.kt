package com.obsidiancompanion.util

/** 展示格式化：相对时间（「3 小时前」）与文件大小（「12.4 KB」）。 */
object Format {

    fun relativeTime(then: Long?, now: Long = System.currentTimeMillis()): String? {
        if (then == null) return null
        val diff = now - then
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff < minute -> "刚刚"
            diff < hour -> "${diff / minute} 分钟前"
            diff < day -> "${diff / hour} 小时前"
            diff < 2 * day -> "昨天"
            diff < 30 * day -> "${diff / day} 天前"
            else -> {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                sdf.format(java.util.Date(then))
            }
        }
    }

    fun bytes(len: Long?): String {
        if (len == null) return "—"
        val kb = 1024.0
        val mb = kb * 1024
        return when {
            len < 1024 -> "$len B"
            len < mb -> String.format(java.util.Locale.CHINA, "%.1f KB", len / kb)
            else -> String.format(java.util.Locale.CHINA, "%.1f MB", len / mb)
        }
    }
}
