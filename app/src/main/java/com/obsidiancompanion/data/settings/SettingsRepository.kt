package com.obsidiancompanion.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App 设置快照。repoId = "owner/repo"，是所有缓存与用户 metadata 的作用域键（§69/§70）。 */
data class AppSettings(
    val owner: String? = null,
    val repo: String? = null,
    val defaultBranch: String? = null,
    val isOnboarded: Boolean = false,
    val autoRefresh: Boolean = true,
    /** 连接时记录的写权限（permissions.push）；null = 未知，编辑入口不拦截，由保存时的错误提示兜底。 */
    val canWrite: Boolean? = null,
) {
    val repoId: String? get() = if (owner != null && repo != null) "$owner/$repo" else null
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * App 设置（DataStore，§14）：仓库选择（owner/repo/defaultBranch，branch 来自 GitHub metadata，不假设 main）
 * 与 onboarding 标记、自动刷新开关。Token 不在这里 —— 见 CredentialStore。
 */
class SettingsRepository(private val context: Context) {

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            owner = p[OWNER],
            repo = p[REPO],
            defaultBranch = p[BRANCH],
            isOnboarded = p[ONBOARDED] ?: false,
            autoRefresh = p[AUTO_REFRESH] ?: true,
            canWrite = p[CAN_WRITE],
        )
    }

    suspend fun setRepository(owner: String, repo: String, defaultBranch: String, canWrite: Boolean? = null) {
        context.settingsDataStore.edit { p ->
            p[OWNER] = owner
            p[REPO] = repo
            p[BRANCH] = defaultBranch
            if (canWrite != null) p[CAN_WRITE] = canWrite else p.remove(CAN_WRITE)
        }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.settingsDataStore.edit { it[ONBOARDED] = value }
    }

    suspend fun setAutoRefresh(value: Boolean) {
        context.settingsDataStore.edit { it[AUTO_REFRESH] = value }
    }

    /** 重连/重置 onboarding：清仓库选择与完成标记（保留 Token 与用户 metadata）。 */
    suspend fun clearRepository() {
        context.settingsDataStore.edit { p ->
            p.remove(OWNER)
            p.remove(REPO)
            p.remove(BRANCH)
            p.remove(CAN_WRITE)
            p[ONBOARDED] = false
        }
    }

    private companion object {
        val OWNER = stringPreferencesKey("owner")
        val REPO = stringPreferencesKey("repo")
        val BRANCH = stringPreferencesKey("default_branch")
        val ONBOARDED = booleanPreferencesKey("is_onboarded")
        val AUTO_REFRESH = booleanPreferencesKey("auto_refresh")
        val CAN_WRITE = booleanPreferencesKey("can_write")
    }
}
