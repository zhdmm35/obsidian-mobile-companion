package com.obsidiancompanion.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.BuildConfig
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.AppSwitch
import com.obsidiancompanion.core.ui.BackTopBar
import com.obsidiancompanion.core.ui.ConfirmationDialog
import com.obsidiancompanion.core.ui.SecondaryButton
import com.obsidiancompanion.core.ui.SettingRow
import com.obsidiancompanion.data.mock.ConflictDemo
import com.obsidiancompanion.data.settings.AppSettings
import com.obsidiancompanion.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    val settings = AppGraph.settings.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var cacheSizeLabel by mutableStateOf<String?>(null)
        private set

    init { refreshCacheStats() }

    fun refreshCacheStats() {
        viewModelScope.launch { cacheSizeLabel = Format.bytes(AppGraph.contentCache.size()) }
    }

    fun setAutoRefresh(enabled: Boolean, onMessage: (String) -> Unit) {
        viewModelScope.launch {
            AppGraph.settings.setAutoRefresh(enabled)
            // Phase 6B §10：自动刷新 = 启动 / 回到前台 / 网络恢复时自动检查更新；关闭后手动刷新仍可用
            onMessage(
                if (enabled) "自动刷新已开启：启动、回到前台或网络恢复时自动检查更新"
                else "自动刷新已关闭：仍可在数据刷新页手动刷新",
            )
        }
    }

    /** §60/§61：只清 Content Cache —— 不动 Token / 仓库选择 / Tree Cache / 收藏 / 阅读记录 / 搜索历史。 */
    fun clearCache(onDone: (String) -> Unit) {
        viewModelScope.launch {
            AppGraph.contentCache.clear()
            refreshCacheStats()
            onDone("内容缓存已清除，浏览与搜索不受影响")
        }
    }

    /** §67/§68：重连 Repository —— 清旧 Tree Cache + Content Cache + 仓库选择；收藏/阅读按 repoId 隔离保留。 */
    fun reconnectRepository(onDone: () -> Unit) {
        viewModelScope.launch {
            val old = AppGraph.settings.flow.first()
            old.repoId?.let { AppGraph.indexRepository.clearRepositoryData(it) }
            AppGraph.contentCache.clear()
            AppGraph.settings.clearRepository()
            onDone()
        }
    }

    /** DEBUG：仅清 onboarding 标记，快速重走引导（保留全部数据与 Token）。 */
    fun resetOnboarding(onDone: () -> Unit) {
        viewModelScope.launch {
            AppGraph.settings.setOnboarded(false)
            onDone()
        }
    }
}

/**
 * 设置页（原型 scr-settings 分组结构）：仓库 / 刷新 / 本地数据 / GitHub / DEBUG（仅调试构建）。
 * 语义按 Phase 3 调整：后台同步 → 自动刷新；Phase 6B §10：自动刷新 = 启动 / 回到前台 /
 * 网络恢复时自动检查更新（关闭后手动刷新仍可用）。
 * 重新下载 Vault → 清除内容缓存（§60/§61）。
 */
@Composable
fun SettingsScreen(
    onOpenSync: () -> Unit,
    onOpenToken: () -> Unit,
    onReconnectRepository: () -> Unit,
    onResetOnboarding: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showReconnectDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshCacheStats() }

    if (showClearCacheDialog) {
        ConfirmationDialog(
            title = "清除内容缓存？",
            message = "只清除已缓存的笔记正文。浏览、搜索、收藏和阅读记录不受影响；已缓存过的笔记需联网重新获取。",
            confirmText = "清除缓存",
            onConfirm = {
                showClearCacheDialog = false
                viewModel.clearCache(onShowSnackbar)
            },
            onDismiss = { showClearCacheDialog = false },
        )
    }

    if (showReconnectDialog) {
        ConfirmationDialog(
            title = "重新连接 Repository？",
            message = "将清除当前仓库的索引与内容缓存，并重新进入引导。收藏与阅读记录按仓库隔离保留，不会丢失。",
            confirmText = "重新连接",
            onConfirm = {
                showReconnectDialog = false
                viewModel.reconnectRepository(onReconnectRepository)
            },
            onDismiss = { showReconnectDialog = false },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = AppSpacing.screenBottomPadding),
    ) {
        BackTopBar(title = "设置", onBack = null)

        SettingsGroupLabel("仓库")
        SettingRow(label = "Repository", value = settings?.repoId ?: "未连接")
        AppGroupDivider()
        SettingRow(label = "Branch", value = settings?.defaultBranch ?: "—")

        SettingsGroupLabel("刷新")
        SettingRow(
            label = "自动刷新",
            trailing = {
                AppSwitch(
                    checked = settings?.autoRefresh ?: true,
                    onCheckedChange = { viewModel.setAutoRefresh(it, onShowSnackbar) },
                )
            },
        )
        AppGroupDivider()
        SettingRow(label = "刷新状态", value = "查看", onClick = onOpenSync)

        SettingsGroupLabel("本地数据")
        SettingRow(label = "内容缓存", value = viewModel.cacheSizeLabel ?: "计算中……")
        AppGroupDivider()
        SettingRow(label = "清除缓存", onClick = { showClearCacheDialog = true })

        SettingsGroupLabel("GitHub")
        SettingRow(label = "重新设置 Token", onClick = onOpenToken)
        AppGroupDivider()
        SettingRow(label = "重新连接 Repository", onClick = { showReconnectDialog = true })

        if (BuildConfig.DEBUG) {
            SettingsGroupLabel("DEBUG（仅调试构建）")
            SettingRow(
                label = "冲突演示（DEBUG 数据）",
                trailing = {
                    AppSwitch(checked = ConflictDemo.enabled, onCheckedChange = { ConflictDemo.enabled = it })
                },
            )
            Row(Modifier.padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = 8.dp)) {
                SecondaryButton(text = "Reset Onboarding", onClick = onResetOnboarding, small = true)
            }
        }

        Text(
            "Obsidian Mobile Companion · Version 1.0",
            style = AppTypography.caption,
            color = AppColors.textMeta,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 26.dp, bottom = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SettingsGroupLabel(text: String) {
    Text(
        text,
        style = AppTypography.labelSmall,
        color = AppColors.textMeta,
        modifier = Modifier.padding(
            start = AppSpacing.screenPaddingHorizontal,
            end = AppSpacing.screenPaddingHorizontal,
            top = AppSpacing.xxl,
            bottom = 6.dp,
        ),
    )
}

@Composable
private fun AppGroupDivider() {
    com.obsidiancompanion.core.ui.AppHorizontalDivider()
}
