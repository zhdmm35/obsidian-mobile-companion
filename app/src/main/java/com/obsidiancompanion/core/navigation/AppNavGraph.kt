package com.obsidiancompanion.core.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppMotion
import com.obsidiancompanion.core.ui.AppBottomNavigation
import com.obsidiancompanion.core.ui.AppSnackbarHost
import com.obsidiancompanion.core.ui.BottomNavItem
import com.obsidiancompanion.feature.capture.QuickCaptureScreen
import com.obsidiancompanion.feature.editor.EditorScreen
import com.obsidiancompanion.feature.files.FilesScreen
import com.obsidiancompanion.feature.home.HomeScreen
import com.obsidiancompanion.feature.onboarding.OnboardingConfirmScreen
import com.obsidiancompanion.feature.onboarding.OnboardingDoneScreen
import com.obsidiancompanion.feature.onboarding.OnboardingDownloadScreen
import com.obsidiancompanion.feature.onboarding.OnboardingRepoScreen
import com.obsidiancompanion.feature.onboarding.OnboardingTokenScreen
import com.obsidiancompanion.feature.onboarding.OnboardingWelcomeScreen
import com.obsidiancompanion.feature.reader.ReaderScreen
import com.obsidiancompanion.feature.search.SearchScreen
import com.obsidiancompanion.feature.settings.SettingsScreen
import com.obsidiancompanion.feature.sync.ConflictScreen
import com.obsidiancompanion.feature.sync.SyncScreen
import com.obsidiancompanion.feature.viewer.ImageViewerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

/** 应用根：启动门（读设置，不等网络）+ Onboarding / Main 两 graph + 4 tab 底栏 + push 栈 */
@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val context = LocalContext.current

    /** 原型 toast：黑底 pill 约 1.9s 自动消失 */
    fun showSnackbar(message: String) {
        scope.launch {
            val showJob = launch {
                snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            }
            delay(1900)
            showJob.cancel()
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    /** Reader 路径参数需 URL 编码（含 '/'、空格、CJK） */
    fun openNote(path: String, anchor: String? = null) {
        val encoded = URLEncoder.encode(path, "UTF-8")
        navController.navigate(Routes.reader(encoded, anchor?.let { URLEncoder.encode(it, "UTF-8") }))
    }

    /** 图片查看器：与 Reader 同一 URL 编码约定 */
    fun openViewer(path: String) {
        navController.navigate(Routes.viewer(URLEncoder.encode(path, "UTF-8")))
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val tabRoutes = setOf(Routes.HOME, Routes.FILES, Routes.SEARCH, Routes.SETTINGS)
    val showBottomBar = currentRoute in tabRoutes

    // 系统分享接收：暂存文本存在且已进入主界面（不在启动门/Onboarding）时打开快速收集页。
    // 未完成 Onboarding 时保持暂存 —— 完成引导到达主 tab 后自动接着打开。
    // 暂存由 QuickCaptureViewModel 进入时消费（系统返回不会触发本 effect 重开页面）；
    // launchSingleTop 防连续两次分享在首个导航生效前叠出两个收集页。
    val sharedText by AppGraph.pendingSharedText.collectAsState()
    LaunchedEffect(sharedText, currentRoute) {
        if (sharedText != null && currentRoute in tabRoutes) {
            navController.navigate(Routes.QUICK_CAPTURE) { launchSingleTop = true }
        }
    }

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = AppColors.background,
        bottomBar = {
            if (showBottomBar) {
                AppBottomNavigation(
                    items = listOf(
                        BottomNavItem(Routes.HOME, "首页", AppIcons.Home),
                        BottomNavItem(Routes.FILES, "笔记", AppIcons.Folder),
                        BottomNavItem(Routes.SEARCH, "搜索", AppIcons.Search),
                        BottomNavItem(Routes.SETTINGS, "设置", AppIcons.Sliders),
                    ),
                    currentRoute = currentRoute,
                    onItemClick = ::navigateToTab,
                )
            }
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.GATE,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            enterTransition = {
                fadeIn(tween(200, easing = AppMotion.easeStandard)) +
                    slideInHorizontally(tween(200, easing = AppMotion.easeStandard)) { (it * 0.045f).toInt() }
            },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = {
                fadeOut(tween(200)) +
                    slideOutHorizontally(tween(200, easing = AppMotion.easeStandard)) { (it * 0.045f).toInt() }
            },
        ) {
            // ── 启动门：AppSettings 首个值到达即定向（§3：读缓存立即展示，不等网络）──
            composable(Routes.GATE) {
                val settings by AppGraph.settings.flow.collectAsState(initial = null)
                LaunchedEffect(settings) {
                    if (settings != null) {
                        val target = if (settings!!.isOnboarded) Routes.HOME else Routes.ONBOARDING_WELCOME
                        navController.navigate(target) {
                            popUpTo(Routes.GATE) { inclusive = true }
                        }
                    }
                }
                Box(Modifier.fillMaxSize())
            }

            // ── Onboarding（forward-only；Done 清栈进 Main）──────────
            composable(Routes.ONBOARDING_WELCOME) {
                OnboardingWelcomeScreen(onStart = { navController.navigate(Routes.onboardingToken()) })
            }
            composable(
                route = Routes.ONBOARDING_TOKEN,
                arguments = listOf(navArgument("reentry") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                val reentry = entry.arguments?.getBoolean("reentry") ?: false
                OnboardingTokenScreen(
                    onNext = {
                        if (reentry) {
                            navController.popBackStack()
                            showSnackbar("Token 已更新")
                        } else {
                            navController.navigate(Routes.ONBOARDING_REPO)
                        }
                    },
                )
            }
            composable(Routes.ONBOARDING_REPO) {
                OnboardingRepoScreen(onNext = { navController.navigate(Routes.ONBOARDING_CONFIRM) })
            }
            composable(Routes.ONBOARDING_CONFIRM) {
                OnboardingConfirmScreen(onStartDownload = { navController.navigate(Routes.ONBOARDING_DOWNLOAD) })
            }
            composable(Routes.ONBOARDING_DOWNLOAD) {
                OnboardingDownloadScreen(
                    onFinished = {
                        navController.navigate(Routes.ONBOARDING_DONE) { launchSingleTop = true }
                    },
                )
            }
            composable(Routes.ONBOARDING_DONE) {
                OnboardingDoneScreen(
                    onFinished = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }

            // ── Main tabs ────────────────────────────────────────────
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenNote = { openNote(it) },
                    onOpenSearch = { navigateToTab(Routes.SEARCH) },
                    onOpenFiles = { navigateToTab(Routes.FILES) },
                    onOpenSync = { navController.navigate(Routes.SYNC) },
                )
            }
            composable(Routes.FILES) {
                FilesScreen(
                    onOpenNote = { openNote(it) },
                    onOpenImage = { openViewer(it) },
                    onAttachmentTap = { showSnackbar("V1 仅支持阅读 Markdown 笔记") },
                    onOpenEditor = { path ->
                        navController.navigate(Routes.editor(URLEncoder.encode(path, "UTF-8")))
                    },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenNote = { openNote(it) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenSync = { navController.navigate(Routes.SYNC) },
                    onOpenToken = { navController.navigate(Routes.onboardingToken(reentry = true)) },
                    onReconnectRepository = {
                        navController.navigate(Routes.ONBOARDING_WELCOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onResetOnboarding = {
                        navController.navigate(Routes.ONBOARDING_WELCOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onShowSnackbar = ::showSnackbar,
                )
            }

            // ── Push 栈 ──────────────────────────────────────────────
            composable(
                route = Routes.VIEWER,
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) { entry ->
                ImageViewerScreen(
                    initialPath = URLDecoder.decode(entry.arguments?.getString("path").orEmpty(), "UTF-8"),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.READER,
                arguments = listOf(
                    navArgument("noteId") { type = NavType.StringType },
                    navArgument("anchor") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val noteId = entry.arguments?.getString("noteId").orEmpty()
                val anchor = entry.arguments?.getString("anchor")
                val decodedPath = URLDecoder.decode(noteId, "UTF-8")
                ReaderScreen(
                    notePath = decodedPath,
                    anchor = anchor?.let { URLDecoder.decode(it, "UTF-8") },
                    onBack = { navController.popBackStack() },
                    onEdit = {
                        // Phase 5 §4：Reader → More → 编辑 → 既有 EditorScreen
                        navController.navigate(Routes.editor(URLEncoder.encode(decodedPath, "UTF-8")))
                    },
                    onOpenNote = { path, heading ->
                        openNote(path, heading)
                    },
                    onOpenImage = { openViewer(it) },
                    onOpenExternalUrl = { url ->
                        try {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                            )
                        } catch (e: android.content.ActivityNotFoundException) {
                            showSnackbar("设备上没有可打开该链接的应用")
                        }
                    },
                    onShare = { title, markdown ->
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TITLE, title)
                            putExtra(android.content.Intent.EXTRA_TEXT, markdown)
                        }
                        try {
                            context.startActivity(android.content.Intent.createChooser(send, "分享笔记"))
                        } catch (e: android.content.ActivityNotFoundException) {
                            showSnackbar("设备上没有可分享的应用")
                        }
                    },
                    onShowSnackbar = ::showSnackbar,
                )
            }
            composable(
                route = Routes.EDITOR,
                arguments = listOf(navArgument("noteId") { type = NavType.StringType }),
            ) { entry ->
                val noteId = entry.arguments?.getString("noteId").orEmpty()
                val decodedPath = URLDecoder.decode(noteId, "UTF-8")
                EditorScreen(
                    notePath = decodedPath,
                    onLeave = { navController.popBackStack() },
                    onOpenConflict = {
                        navController.navigate(Routes.conflict(URLEncoder.encode(decodedPath, "UTF-8")))
                    },
                    onOpenToken = { navController.navigate(Routes.onboardingToken(reentry = true)) },
                    onShowSnackbar = ::showSnackbar,
                )
            }
            composable(Routes.SYNC) {
                SyncScreen(
                    onBack = { navController.popBackStack() },
                    onOpenConflict = { navController.navigate(Routes.CONFLICT_DEMO) },
                    onShowSnackbar = ::showSnackbar,
                )
            }
            // 快速收集：系统分享文本 → 新笔记
            composable(Routes.QUICK_CAPTURE) {
                QuickCaptureScreen(
                    onLeave = { navController.popBackStack() },
                    onShowSnackbar = ::showSnackbar,
                )
            }
            // Phase 5 §16：真实冲突（Editor 保存 409 后进入）；解决后回到 Reader（Editor 出栈）
            composable(
                route = Routes.CONFLICT,
                arguments = listOf(navArgument("noteId") { type = NavType.StringType }),
            ) { entry ->
                val noteId = entry.arguments?.getString("noteId").orEmpty()
                ConflictScreen(
                    notePath = URLDecoder.decode(noteId, "UTF-8"),
                    onBack = { navController.popBackStack() },
                    onResolved = { navController.popBackStack(Routes.EDITOR, inclusive = true) },
                    onShowSnackbar = ::showSnackbar,
                )
            }
            // DEBUG 冲突演示（无真实数据）
            composable(Routes.CONFLICT_DEMO) {
                ConflictScreen(
                    notePath = null,
                    onBack = { navController.popBackStack() },
                    onResolved = { navController.popBackStack() },
                    onShowSnackbar = ::showSnackbar,
                )
            }
        }
    }
}
