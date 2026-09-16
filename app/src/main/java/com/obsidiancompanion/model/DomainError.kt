package com.obsidiancompanion.model

/**
 * 统一 Domain Error（§64）：HTTP/IO/序列化异常不直接进 UI，先映射到这里，UI 再转人话文案。
 */
enum class DomainError {
    /** 401 —— Token 无效或已过期 */
    Unauthorized,

    /** 403（非限流）—— 无权限访问 */
    Forbidden,

    /** 404 —— 仓库 / 文件不存在 */
    NotFound,

    /** 403（限流）或 429 */
    RateLimited,

    /** 网络不可达 / IO 异常 */
    NetworkUnavailable,

    /** 5xx */
    ServerError,

    /** 响应体解析失败 */
    MalformedResponse,

    /** 409 —— 保存时 base SHA 已过期（远端在编辑期间被修改），绝不静默覆盖 */
    Conflict,

    Unknown,
}
