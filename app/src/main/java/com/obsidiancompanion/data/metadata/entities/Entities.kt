package com.obsidiancompanion.data.metadata.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Repository Tree 条目类型（§17：按扩展名从 Tree API 结果构建 UI 类型）。 */
enum class EntryKind { DIRECTORY, MARKDOWN, IMAGE, PDF, CANVAS, OTHER }

/**
 * Repository Tree 缓存（§17）。联合主键 repoId+path（§70：模型不隐含「世界上只有一个 Repository」）。
 * observedChangedAt（§22）= App 检测到该条目 blob SHA 变化的时间，不是 filesystem mtime；
 * 首次加载一律 null（§23），Unchanged 保留旧值，Changed/后续 Added 置为检测时刻。
 */
@Entity(tableName = "repo_entries", primaryKeys = ["repoId", "path"])
data class RepoEntryEntity(
    val repoId: String,
    val path: String,
    val name: String,
    val parentPath: String?,
    val kind: EntryKind,
    val blobSha: String,
    val size: Long?,
    val observedChangedAt: Long?,
)

/**
 * 用户 metadata（§40/§41/§69）：identity = repoId + path（内容更新 ABC→DEF 收藏仍在；
 * 不与 blobSha 绑定）。收藏/最近阅读纯属 App 本地数据，不写 GitHub、不写 Markdown。
 */
@Entity(tableName = "note_user_metadata", primaryKeys = ["repoId", "path"])
data class NoteUserMetadataEntity(
    val repoId: String,
    val path: String,
    val lastReadAt: Long?,
    val isFavorite: Boolean = false,
)

/** 最近搜索（§28）：同 query 重复提交只更新时间；每仓库最多展示 10 条。 */
@Entity(tableName = "recent_searches", primaryKeys = ["repoId", "query"])
data class RecentSearchEntity(
    val repoId: String,
    val query: String,
    val searchedAt: Long,
)

/** Tree 缓存的 freshness 元数据（§18）。 */
@Entity(tableName = "repository_state", primaryKeys = ["repoId"])
data class RepositoryStateEntity(
    val repoId: String,
    val branch: String,
    val rootTreeSha: String?,
    val etag: String?,
    val lastRefreshAt: Long?,
    /** SUCCESS / NOT_MODIFIED / FAILED_<DomainError> */
    val lastRefreshResult: String?,
    val entryCount: Int = 0,
    val markdownCount: Int = 0,
)

/**
 * Phase 5 §20-§21：发送 GitHub Write 前暂存的待保存内容（最低限度 Draft）。
 * 编辑停笔后自动暂存；可从草稿中心继续编辑、复制或明确丢弃，保存成功清除。
 * 每个仓库路径保留一份草稿；撤销历史仅在当前编辑会话内存中。
 */
@Entity(tableName = "pending_edits", primaryKeys = ["repoId", "path"])
data class PendingEditEntity(
    val repoId: String,
    val path: String,
    /** 本次编辑 session 冻结的 base blob SHA（§39）。 */
    val baseSha: String,
    val content: String,
    val updatedAt: Long,
)

/** 分享正文独立存储；改名字/目录只更新这一条，列表仅查询摘要。 */
@Entity(tableName = "capture_drafts")
data class CaptureDraftEntity(
    @PrimaryKey val id: String,
    val text: String,
    val name: String,
    val folder: String,
    val updatedAt: Long,
)

data class CaptureDraftSummary(val id: String, val name: String, val folder: String, val preview: String, val updatedAt: Long)
data class PendingEditSummary(val repoId: String, val path: String, val preview: String, val updatedAt: Long)
