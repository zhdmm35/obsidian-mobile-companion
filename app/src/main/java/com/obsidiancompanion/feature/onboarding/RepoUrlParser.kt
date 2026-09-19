package com.obsidiancompanion.feature.onboarding

/**
 * 把用户粘贴的仓库地址解析为 (owner, repo)。接受：
 *  - `owner/repo`
 *  - `https://github.com/owner/repo`（http / 无 scheme 的 `github.com/…` 亦可）
 *  - `git@github.com:owner/repo.git`
 *  - 允许尾部 `/`、`.git` 以及 `/tree/…`、`/issues` 等额外路径段（取前两段）
 * 无法识别时返回 null，由调用方给出提示。
 */
fun parseRepoInput(raw: String): Pair<String, String>? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    s = s.removePrefix("git@github.com:")
    s = s.removePrefix("https://").removePrefix("http://")
    s = s.removePrefix("github.com/")
    s = s.trim('/')
    s = s.removeSuffix(".git")
    val segments = s.split("/").filter { it.isNotBlank() }
    if (segments.size < 2) return null
    val owner = segments[0]
    val repo = segments[1]
    // owner 按 GitHub 实际规则（字母数字 + 连字符）：顺带挡掉 gitlab.com 等其它站点的链接；
    // repo 允许 . _ -。更细的合法性由 API 404 兜底。
    if (!Regex("^[A-Za-z0-9-]+$").matches(owner)) return null
    if (!Regex("^[A-Za-z0-9._-]+$").matches(repo)) return null
    return owner to repo
}
