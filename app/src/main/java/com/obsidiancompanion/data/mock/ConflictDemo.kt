package com.obsidiancompanion.data.mock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 冲突演示状态（§55）：Phase 3 在线只读模式不会产生真实冲突；
 * 保留 Mock 数据与 DEBUG 开关驱动 chip / SyncScreen 冲突卡 / ConflictScreen，
 * 供视觉走查与 Phase 5 编辑阶段衔接。
 */
object ConflictDemo {

    /** DEBUG：模拟「有冲突」状态（仅设置页 DEBUG 分组可切换） */
    var enabled by mutableStateOf(false)

    val LOCAL = """# Agent设计

## 阅读优先
- [x] Reader 先做，编辑后置
- [ ] 搜索支持拼音首字母

## 同步
采用「后台静默同步」：打开 App 不等待，
首页立即展示本地内容。""".trimIndent()

    val REMOTE = """# Agent设计

## 阅读优先
- Reader 与编辑同期开发
- 搜索使用全文索引

## 同步
打开 App 时先检查远端更新，再进入首页。""".trimIndent()

    const val REMOTE_FILE = "Agent设计.conflict-remote-2026-09-02-2236.md"
}
