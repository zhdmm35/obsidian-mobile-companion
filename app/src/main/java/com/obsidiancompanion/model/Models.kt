package com.obsidiancompanion.model

/**
 * 用户可见的 5 种数据状态 —— 在线模式语义（§55）：
 * 已更新 / 正在刷新 / 离线 / 刷新失败 / 有冲突（Phase 3 不真实产生，保留演示与 Conflict Screen 给 Phase 5）。
 */
enum class SyncStatus { UPDATED, REFRESHING, OFFLINE, REFRESH_FAILED, CONFLICT }
