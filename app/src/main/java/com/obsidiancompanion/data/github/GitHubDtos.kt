package com.obsidiancompanion.data.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ── Domain 模型（Remote 层输出，HTTP DTO 不出这一层 —— §37）────────── */

data class GithubUser(val login: String)

data class GithubRepo(
    val id: Long,
    val owner: String,
    val name: String,
    val isPrivate: Boolean,
    val description: String?,
    val defaultBranch: String?,
    val updatedAt: String?,
)

data class RemoteTreeEntry(
    val path: String,
    val isDirectory: Boolean,
    val sha: String,
    val size: Long?,
)

/** 一次完整的 Tree 拉取结果（truncated 已在内部用非递归逐层 fallback 解决 —— §16）。 */
data class RemoteTree(
    val rootSha: String,
    val entries: List<RemoteTreeEntry>,
    val etag: String?,
)

/** Contents write 成功结果：newSha = 新正文 blob SHA（Tree entry / Cache 的键），commitSha 仅 debug 记录（§52）。 */
data class UpdatedFile(
    val newSha: String,
    val commitSha: String?,
)

/** Contents read（JSON 形态）：冲突流程用它一次拿到远端最新 sha + 正文（§16-18）。 */
data class RemoteFileContent(
    val sha: String,
    val bytes: ByteArray,
)

/* ── HTTP DTO（kotlinx.serialization，容忍未知字段）────────────────── */

@Serializable
data class UserDto(val login: String)

@Serializable
data class RepoOwnerDto(val login: String)

@Serializable
data class RepoDto(
    val id: Long,
    val name: String,
    val owner: RepoOwnerDto,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("private") val isPrivate: Boolean = false,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val description: String? = null,
)

@Serializable
data class TreeEntryDto(
    val path: String,
    val mode: String? = null,
    val type: String,
    val sha: String,
    val size: Long? = null,
)

@Serializable
data class TreeResponseDto(
    val sha: String,
    val tree: List<TreeEntryDto> = emptyList(),
    val truncated: Boolean = false,
)

/* ── Contents write / JSON read（Phase 5）───────────────────────────── */

/** PUT contents 请求体：content 为 Base64（UTF-8 字节），sha 为编辑时冻结的 base blob SHA。 */
@Serializable
data class UpdateFileRequestDto(
    val message: String,
    val content: String,
    val sha: String,
    val branch: String,
)

@Serializable
data class ContentItemDto(
    val name: String? = null,
    val path: String? = null,
    val sha: String,
    val content: String? = null,
    val encoding: String? = null,
)

@Serializable
data class CommitInfoDto(
    val sha: String? = null,
)

@Serializable
data class UpdateFileResponseDto(
    val content: ContentItemDto? = null,
    val commit: CommitInfoDto? = null,
)

internal fun RepoDto.toDomain(): GithubRepo = GithubRepo(
    id = id,
    owner = owner.login,
    name = name,
    isPrivate = isPrivate,
    description = description,
    defaultBranch = defaultBranch,
    updatedAt = updatedAt,
)

internal fun TreeEntryDto.toDomain(): RemoteTreeEntry = RemoteTreeEntry(
    path = path,
    isDirectory = type == "tree",
    sha = sha,
    size = size,
)
