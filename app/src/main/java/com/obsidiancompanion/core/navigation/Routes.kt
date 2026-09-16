package com.obsidiancompanion.core.navigation

/** 路由常量。Reader 参数为 URL 编码的仓库相对路径（含 '/' 与 CJK）。 */
object Routes {
    /** 启动门：读 AppSettings 决定进入 Onboarding 还是 Home（不等待网络） */
    const val GATE = "gate"

    // ── Onboarding（forward-only 线性流）──────────────────────
    const val ONBOARDING_WELCOME = "onboarding/welcome"
    const val ONBOARDING_TOKEN = "onboarding/token?reentry={reentry}"
    const val ONBOARDING_REPO = "onboarding/repo"
    const val ONBOARDING_CONFIRM = "onboarding/confirm"
    const val ONBOARDING_DOWNLOAD = "onboarding/download"
    const val ONBOARDING_DONE = "onboarding/done"

    // ── Main tabs ─────────────────────────────────────────────
    const val HOME = "home"
    const val FILES = "files"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    // ── Push 栈（无底栏）──────────────────────────────────────
    const val READER = "reader/{noteId}?anchor={anchor}"
    const val EDITOR = "editor/{noteId}"

    /** 图片查看器（path = URL 编码的仓库相对路径，与 Reader 同一约定）。 */
    const val VIEWER = "viewer/{path}"

    /** Phase 5 §16：真实冲突页（noteId = URL 编码的仓库相对路径）。 */
    const val CONFLICT = "conflict/{noteId}"

    /** DEBUG 冲突演示（无真实 noteId —— 由设置页「冲突演示」开关驱动）。 */
    const val CONFLICT_DEMO = "conflict"
    const val SYNC = "sync"

    fun reader(noteId: String, anchor: String? = null): String =
        if (anchor == null) "reader/$noteId" else "reader/$noteId?anchor=$anchor"

    fun editor(noteId: String): String = "editor/$noteId"

    fun viewer(path: String): String = "viewer/$path"

    fun conflict(noteId: String): String = "conflict/$noteId"

    /** token 屏导航（reentry = 从设置「重新设置 Token」进入，完成后返回设置） */
    fun onboardingToken(reentry: Boolean = false): String =
        if (reentry) "onboarding/token?reentry=true" else "onboarding/token"
}
