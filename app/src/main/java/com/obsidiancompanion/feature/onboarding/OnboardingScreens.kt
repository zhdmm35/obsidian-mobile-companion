package com.obsidiancompanion.feature.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.R
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.DotsIndicator
import com.obsidiancompanion.core.ui.GhostButton
import com.obsidiancompanion.core.ui.PrimaryButton
import com.obsidiancompanion.data.github.GithubRepo
import com.obsidiancompanion.model.DomainError
import kotlinx.coroutines.launch

/** Token 校验后的仓库列表状态（§12：Loading / Content / Error / Empty 全覆盖）。 */
sealed interface RepoListUiState {
    data object Loading : RepoListUiState
    data class Content(val repos: List<GithubRepo>) : RepoListUiState
    data object Empty : RepoListUiState
    data class Error(val message: String) : RepoListUiState
}

/**
 * Onboarding 共享 ViewModel（Activity 级，Token/Repo/Confirm 跨屏保留状态）：
 * Token 验证（真实 /user）→ 仓库列表（真实 /user/repos）→ 选择 → metadata（default_branch）。
 */
class OnboardingViewModel : ViewModel() {

    var tokenInput by mutableStateOf("")
    var tokenError by mutableStateOf<String?>(null)
        private set
    var validating by mutableStateOf(false)
        private set
    var hasSavedToken by mutableStateOf(false)
        private set

    var repoState by mutableStateOf<RepoListUiState?>(null)
        private set
    var selectedRepo by mutableStateOf<GithubRepo?>(null)
        private set

    /** 选中仓库的 metadata（default_branch 来源，不假设 main —— Phase 1 修正 #3）。 */
    var repoDetail by mutableStateOf<GithubRepo?>(null)
        private set

    init {
        hasSavedToken = AppGraph.credentials.getToken() != null
    }

    fun onTokenChange(value: String) {
        tokenInput = value
        if (tokenError != null) tokenError = null
    }

    /** 验证输入的 Token：保存 → GET /user → 成功加载仓库列表并回调导航。 */
    fun submitToken(onSuccess: () -> Unit) {
        val token = tokenInput.trim()
        if (token.isEmpty()) {
            tokenError = "请先输入 GitHub Token"
            return
        }
        validating = true
        tokenError = null
        AppGraph.credentials.saveToken(token)
        viewModelScope.launch {
            when (val r = AppGraph.githubRemote.validateToken()) {
                is com.obsidiancompanion.data.github.GitHubResult.Ok -> {
                    hasSavedToken = true
                    loadRepos()
                    validating = false
                    onSuccess()
                }
                is com.obsidiancompanion.data.github.GitHubResult.Fail -> {
                    AppGraph.credentials.clearToken()
                    hasSavedToken = false
                    validating = false
                    tokenError = r.error.tokenMessage()
                }
                is com.obsidiancompanion.data.github.GitHubResult.NotModified -> validating = false
            }
        }
    }

    /** 使用已保存 Token 继续（重连场景，§67）。 */
    fun continueWithSavedToken(onSuccess: () -> Unit, onInvalid: (String) -> Unit) {
        validating = true
        viewModelScope.launch {
            when (val r = AppGraph.githubRemote.validateToken()) {
                is com.obsidiancompanion.data.github.GitHubResult.Ok -> {
                    loadRepos()
                    validating = false
                    onSuccess()
                }
                is com.obsidiancompanion.data.github.GitHubResult.Fail -> {
                    validating = false
                    onInvalid(r.error.tokenMessage())
                }
                is com.obsidiancompanion.data.github.GitHubResult.NotModified -> validating = false
            }
        }
    }

    fun loadRepos() {
        repoState = RepoListUiState.Loading
        viewModelScope.launch {
            when (val r = AppGraph.githubRemote.listRepositories()) {
                is com.obsidiancompanion.data.github.GitHubResult.Ok ->
                    repoState = if (r.value.isEmpty()) RepoListUiState.Empty else RepoListUiState.Content(r.value)
                is com.obsidiancompanion.data.github.GitHubResult.Fail ->
                    repoState = RepoListUiState.Error(r.error.tokenMessage())
                is com.obsidiancompanion.data.github.GitHubResult.NotModified -> Unit
            }
        }
    }

    fun selectRepo(repo: GithubRepo) {
        selectedRepo = repo
        repoDetail = null
        viewModelScope.launch {
            // metadata 读取（default_branch）；失败不阻塞 —— Download 阶段会重试
            when (val r = AppGraph.githubRemote.getRepository(repo.owner, repo.name)) {
                is com.obsidiancompanion.data.github.GitHubResult.Ok -> repoDetail = r.value
                else -> Unit
            }
        }
    }

    fun retryRepos() = loadRepos()
}

/** Token 阶段错误文案（§65：无效 Token 明确提示，不进入仓库选择）。 */
private fun DomainError.tokenMessage(): String = when (this) {
    DomainError.Unauthorized -> "Token 无效或已过期，请检查后重新输入"
    DomainError.Forbidden -> "Token 没有访问权限，请确认已授权该仓库"
    DomainError.RateLimited -> "GitHub 接口限流，请稍后再试"
    DomainError.NetworkUnavailable -> "网络不可用，请检查网络后重试"
    else -> "无法连接 GitHub，请稍后再试"
}

/** 极淡品牌纸纹背景（artwork/bg-welcome.jpg）；纸纹是浅色图，深色主题下换 palette 底色保文字对比。 */
@Composable
internal fun PaperBackground() {
    if (isSystemInDarkTheme()) {
        Box(Modifier.fillMaxSize().background(AppColors.background))
    } else {
        Image(
            painter = painterResource(R.drawable.bg_welcome),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Onboarding 1/6 —— 欢迎（文案按在线优先语义更新）；极淡的品牌纸纹背景（artwork/bg-welcome.jpg） */
@Composable
fun OnboardingWelcomeScreen(
    onStart: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        PaperBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            // 品牌主视觉（artwork/hero-welcome.jpg 同款）：书 ↔ 手机同步插画，圆角卡片呈现
            Image(
                painter = painterResource(R.drawable.hero_welcome),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(190.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .border(1.dp, AppColors.borderStrong, RoundedCornerShape(28.dp)),
            )
            Spacer(Modifier.height(24.dp))
            Text("欢迎", style = AppTypography.displayLarge)
            Spacer(Modifier.height(10.dp))
            Text(
                "在手机上阅读你的 Obsidian Vault。\n笔记保存在 GitHub 仓库里，打开过的内容会缓存到手机，没有网络也能翻阅。",
                style = AppTypography.bodyBase,
                color = AppColors.textTertiary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(28.dp))
            PrimaryButton(text = "连接 GitHub", onClick = onStart, block = true)
            Spacer(Modifier.height(28.dp))
            DotsIndicator(count = 5, current = 0)
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Onboarding 2/6 —— GitHub Token（真实验证；文案按 Phase 1 修正 #1：Contents Read and write） */
@Composable
fun OnboardingTokenScreen(
    onNext: () -> Unit,
) {
    // Onboarding 三屏（Token/Repo/Confirm）共享 Activity 级 ViewModel，跨屏保留输入与选择
    val viewModel: OnboardingViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner = androidx.compose.ui.platform.LocalContext.current as androidx.lifecycle.ViewModelStoreOwner,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 30.dp, end = 30.dp, top = 76.dp),
    ) {
        Text("GitHub Token", style = AppTypography.displayLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            "Token 只用于访问你的私人 Vault 仓库，不会用于其他用途。",
            style = AppTypography.bodyBase,
            color = AppColors.textTertiary,
        )
        Spacer(Modifier.height(20.dp))
        BasicTextField(
            value = viewModel.tokenInput,
            onValueChange = viewModel::onTokenChange,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = AppTypography.codeInline.copy(color = AppColors.textPrimary),
            cursorBrush = SolidColor(AppColors.accent),
            singleLine = true,
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .clip(AppShapes.medium)
                        .background(AppColors.surface)
                        .border(1.dp, if (viewModel.tokenError == null) AppColors.borderStrong else AppColors.danger, AppShapes.medium),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.padding(horizontal = 14.dp)) {
                        if (viewModel.tokenInput.isEmpty()) {
                            Text("github_pat_••••••••••••", style = AppTypography.codeInline, color = AppColors.textMeta)
                        }
                        inner()
                    }
                }
            },
        )
        if (viewModel.tokenError != null) {
            Text(
                viewModel.tokenError!!,
                style = AppTypography.caption,
                color = AppColors.danger,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        } else {
            Spacer(Modifier.height(12.dp))
        }
        PrimaryButton(
            text = if (viewModel.validating) "正在验证……" else "下一步",
            onClick = { viewModel.submitToken(onNext) },
            block = true,
        )
        if (viewModel.hasSavedToken) {
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "使用已保存的 Token 继续",
                onClick = { viewModel.continueWithSavedToken(onNext) { viewModel.onTokenChange("") } },
                small = true,
            )
            Spacer(Modifier.height(6.dp))
        } else {
            Spacer(Modifier.height(14.dp))
        }
        Text(
            "在 GitHub → Settings → Developer settings 中创建 Fine-grained Token：仅选择此 Vault 仓库，并勾选 Repository contents: Read and write（读取与保存笔记）。",
            style = AppTypography.caption,
            color = AppColors.textTertiary,
        )
        Spacer(Modifier.height(28.dp))
        DotsIndicator(count = 5, current = 1)
    }
}
