package com.obsidiancompanion.feature.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.R
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.Badge
import com.obsidiancompanion.core.ui.DotsIndicator
import com.obsidiancompanion.core.ui.EmptyState
import com.obsidiancompanion.core.ui.PrimaryButton
import com.obsidiancompanion.core.ui.SecondaryButton
import com.obsidiancompanion.core.ui.SyncProgressBar
import com.obsidiancompanion.data.github.GitHubResult
import com.obsidiancompanion.data.repository.RefreshOutcome
import com.obsidiancompanion.data.settings.AppSettings
import com.obsidiancompanion.feature.sync.SpinningIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Onboarding 5/6 —— 真实索引构建（§9）：
 * 读取 repository metadata（default_branch）→ 读取 Repository Tree → Room 建索引 → Done。
 * 只拉 Tree 不下载任何正文（§59/§62）。
 */
class OnboardingDownloadViewModel : ViewModel() {

    var stage by mutableStateOf("正在验证仓库……")
        private set
    var progress by mutableStateOf(0.1f)
        private set
    var failedMessage by mutableStateOf<String?>(null)
        private set
    var finished by mutableStateOf(false)
        private set
    var markdownCount by mutableStateOf(0)
        private set
    var attachmentCount by mutableStateOf(0)
        private set
    var branch by mutableStateOf<String?>(null)
        private set

    private var started = false

    fun start(shared: OnboardingViewModel, onFinished: () -> Unit) {
        if (started) return
        started = true
        viewModelScope.launch {
            run(shared, onFinished)
        }
    }

    fun retry(shared: OnboardingViewModel, onFinished: () -> Unit) {
        failedMessage = null
        progress = 0.1f
        viewModelScope.launch {
            run(shared, onFinished)
        }
    }

    private suspend fun run(shared: OnboardingViewModel, onFinished: () -> Unit) {
        val selected = shared.selectedRepo
        if (selected == null) {
            failedMessage = "尚未选择仓库，请返回上一步"
            return
        }
        try {
            stage = "正在读取仓库信息……"
            progress = 0.3f
            // default_branch：优先用选择时已取的 metadata；否则现取
            val detail = shared.repoDetail
                ?: when (val r = AppGraph.githubRemote.getRepository(selected.owner, selected.name)) {
                    is GitHubResult.Ok -> r.value
                    is GitHubResult.Fail -> {
                        failedMessage = r.error.let {
                            if (it == com.obsidiancompanion.model.DomainError.NetworkUnavailable) {
                                "网络不可用，请检查网络后重试"
                            } else {
                                "无法读取仓库信息（${it.name}）"
                            }
                        }
                        return
                    }
                    is GitHubResult.NotModified -> null
                }
            val defaultBranch = detail?.defaultBranch ?: "main"
            branch = defaultBranch

            stage = "正在读取 Repository Tree……"
            progress = 0.6f
            AppGraph.settings.setRepository(selected.owner, selected.name, defaultBranch)

            // 用户刚选择仓库连接：必须真实拉取，不受 freshness window 去重（Phase 6B §5）
            when (val outcome = AppGraph.indexRepository.refreshTree(force = true)) {
                is RefreshOutcome.Success -> {
                    markdownCount = outcome.diff.entries.count { it.kind == com.obsidiancompanion.data.metadata.entities.EntryKind.MARKDOWN }
                    attachmentCount = outcome.diff.entries.count {
                        it.kind != com.obsidiancompanion.data.metadata.entities.EntryKind.MARKDOWN &&
                            it.kind != com.obsidiancompanion.data.metadata.entities.EntryKind.DIRECTORY
                    }
                    stage = "正在建立本地索引……"
                    progress = 0.95f
                    delay(350)
                    AppGraph.settings.setOnboarded(true)
                    finished = true
                    onFinished()
                }
                is RefreshOutcome.NotModified -> {
                    // 理论上首次不会出现；按成功处理
                    finished = true
                    onFinished()
                }
                is RefreshOutcome.Failed -> {
                    failedMessage = when (outcome.error) {
                        com.obsidiancompanion.model.DomainError.NetworkUnavailable -> "网络不可用，请检查网络后重试"
                        com.obsidiancompanion.model.DomainError.Unauthorized -> "Token 无效或已过期"
                        com.obsidiancompanion.model.DomainError.NotFound -> "仓库不存在或 Token 已失去权限"
                        com.obsidiancompanion.model.DomainError.RateLimited -> "GitHub 接口限流，请稍后再试"
                        else -> "读取 Tree 失败（${outcome.error.name}），请稍后重试"
                    }
                }
            }
        } catch (e: Exception) {
            failedMessage = "出了点问题，请稍后重试"
        }
    }
}

/** Onboarding 3/6 —— 选择仓库（真实列表：Loading / Content / Empty / Error 四态） */
@Composable
fun OnboardingRepoScreen(
    onNext: () -> Unit,
) {
    val sharedViewModel: OnboardingViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner = androidx.compose.ui.platform.LocalContext.current as androidx.lifecycle.ViewModelStoreOwner,
    )
    LaunchedEffect(Unit) {
        if (sharedViewModel.repoState == null) sharedViewModel.loadRepos()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 30.dp, end = 30.dp, top = 64.dp),
    ) {
        Text("选择仓库", style = AppTypography.displayLarge)
        Spacer(Modifier.height(16.dp))

        when (val state = sharedViewModel.repoState) {
            null, is RepoListUiState.Loading -> {
                Spacer(Modifier.height(40.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpinningIcon(
                        AppIcons.Refresh,
                        tint = AppColors.textMeta,
                        size = 22.dp,
                        contentDescription = null,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "正在读取可访问的仓库……",
                    style = AppTypography.bodySmall,
                    color = AppColors.textTertiary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            is RepoListUiState.Empty -> EmptyState(
                icon = AppIcons.Folder,
                title = "没有可访问的仓库",
                subtitle = "请确认 Token 已授权至少一个仓库",
            )

            is RepoListUiState.Error -> {
                EmptyState(
                    icon = AppIcons.Alert,
                    title = "仓库列表加载失败",
                    subtitle = state.message,
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(text = "重试", onClick = { sharedViewModel.retryRepos() }, block = true)
            }

            is RepoListUiState.Content -> state.repos.forEach { repo ->
                val selected = sharedViewModel.selectedRepo?.id == repo.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AppShapes.medium)
                        .background(AppColors.surface)
                        .border(1.dp, if (selected) AppColors.accent else AppColors.border, AppShapes.medium)
                        .clickable { sharedViewModel.selectRepo(repo) }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(repo.name, style = AppTypography.rowTitleSmall)
                        Text(
                            repo.owner + if (repo.description != null) " · ${repo.description}" else "",
                            style = AppTypography.caption,
                            color = AppColors.textTertiary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    Badge(text = if (repo.isPrivate) "Private" else "Public")
                    if (selected) {
                        Icon(AppIcons.Check, contentDescription = "已选择", tint = AppColors.accent, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            text = "下一步",
            enabled = sharedViewModel.selectedRepo != null,
            onClick = onNext,
            block = true,
        )
        Spacer(Modifier.height(28.dp))
        DotsIndicator(count = 5, current = 2)
    }
}

/** Onboarding 4/6 —— 确认（真实 metadata：owner/repo、Private、default branch） */
@Composable
fun OnboardingConfirmScreen(
    onStartDownload: () -> Unit,
) {
    val sharedViewModel: OnboardingViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner = androidx.compose.ui.platform.LocalContext.current as androidx.lifecycle.ViewModelStoreOwner,
    )
    val repo = sharedViewModel.selectedRepo
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text("连接这个 Vault？", style = AppTypography.displayLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            buildString {
                append(repo?.owner ?: "—")
                append("/")
                append(repo?.name ?: "—")
                append("\n")
                append(if (repo?.isPrivate == true) "Private" else "Public")
                append(" · 默认分支 ")
                append(sharedViewModel.repoDetail?.defaultBranch ?: "读取中……")
            },
            style = AppTypography.bodyBase,
            color = AppColors.textTertiary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "将读取仓库的文件列表并建立本地索引；正文在你打开时按需加载。",
            style = AppTypography.bodySmall,
            color = AppColors.textMeta,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton(text = "开始同步", onClick = onStartDownload, block = true)
        Spacer(Modifier.height(28.dp))
        DotsIndicator(count = 5, current = 3)
        Spacer(Modifier.weight(1f))
    }
}

/** Onboarding 5/6 —— 真实 Tree 索引构建（阶段进度 + 失败/重试） */
@Composable
fun OnboardingDownloadScreen(
    onFinished: () -> Unit,
) {
    val sharedViewModel: OnboardingViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner = androidx.compose.ui.platform.LocalContext.current as androidx.lifecycle.ViewModelStoreOwner,
    )
    val viewModel: OnboardingDownloadViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    LaunchedEffect(Unit) { viewModel.start(sharedViewModel, onFinished) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        SpinningMark()
        Spacer(Modifier.height(16.dp))
        Text("正在准备你的 Vault", style = AppTypography.titleMedium.copy(fontSize = 19.sp))
        Spacer(Modifier.height(6.dp))
        Text(
            viewModel.stage,
            style = AppTypography.bodyBase,
            color = AppColors.textTertiary,
        )
        Spacer(Modifier.height(18.dp))
        SyncProgressBar(progress = viewModel.progress)
        Spacer(Modifier.height(24.dp))
        if (viewModel.failedMessage != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(AppIcons.Alert, contentDescription = null, tint = AppColors.danger, modifier = Modifier.size(24.dp))
                Text("没能完成", style = AppTypography.titleMedium.copy(fontSize = 19.sp))
                Text(
                    viewModel.failedMessage!!,
                    style = AppTypography.bodyBase,
                    color = AppColors.textTertiary,
                )
                Spacer(Modifier.height(6.dp))
                SecondaryButton(
                    text = "重试",
                    onClick = { viewModel.retry(sharedViewModel, onFinished) },
                    block = true,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SpinningMark() {
    val transition = rememberInfiniteTransition(label = "downloadSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "downloadSpinAngle",
    )
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(AppColors.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.Refresh,
            contentDescription = null,
            tint = AppColors.onAccent,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { rotationZ = angle },
        )
    }
}

/** Onboarding 6/6 —— 完成（真实计数，来自 Activity 级 DownloadViewModel） */
@Composable
fun OnboardingDoneScreen(
    onFinished: () -> Unit,
) {
    val downloadViewModel: OnboardingDownloadViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner = androidx.compose.ui.platform.LocalContext.current as androidx.lifecycle.ViewModelStoreOwner,
    )
    val markdownCount = downloadViewModel.markdownCount
    Box(Modifier.fillMaxSize()) {
        // 与欢迎页同款的极淡纸纹背景，首尾呼应
        Image(
            painter = painterResource(R.drawable.bg_welcome),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(AppColors.surface)
                    .border(1.dp, AppColors.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Check, contentDescription = null, tint = AppColors.textPrimary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                if (markdownCount > 0) "${markdownCount} 篇笔记已准备好" else "Vault 已连接",
                style = AppTypography.displayLarge,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "文件列表已缓存到手机。正文会在你打开时加载，读过就离线可看。",
                style = AppTypography.bodyBase,
                color = AppColors.textTertiary,
            )
            Spacer(Modifier.height(28.dp))
            PrimaryButton(text = "开始阅读", onClick = onFinished, block = true)
            Spacer(Modifier.height(28.dp))
            DotsIndicator(count = 5, current = 4)
            Spacer(Modifier.weight(1f))
        }
    }
}
