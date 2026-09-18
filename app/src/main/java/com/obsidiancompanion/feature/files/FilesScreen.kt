package com.obsidiancompanion.feature.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.R
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.AppHorizontalDivider
import com.obsidiancompanion.core.ui.AppIconButton
import com.obsidiancompanion.core.ui.EmptyState
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.data.repository.NoteSaveResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 文件浏览器（§25）：完全基于 Tree Cache，进入文件夹不发起任何网络请求。
 * 路径栈保存在 ViewModel（切 tab / 进程重建后保留层级）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModel : ViewModel() {

    var pathSegments by mutableStateOf<List<String>>(emptyList())
        private set

    /** 当前仓库的完整 Tree Cache（Room Flow 驱动，刷新后自动更新） */
    var tree by mutableStateOf<List<RepoEntryEntity>>(emptyList())
        private set

    /**
     * 按父路径分组的预排序索引：tree 每次更新重建一次（O(tree)），
     * entries()/childCount() 退化为 O(1) 查表 —— 不再每行、每次重组都全表 filter/count。
     */
    private var childrenByParent by mutableStateOf<Map<String?, List<RepoEntryEntity>>>(emptyMap())

    init {
        viewModelScope.launch {
            AppGraph.settings.flow.flatMapLatest { s ->
                s.repoId?.let { AppGraph.indexRepository.observeTree(it) } ?: flowOf(emptyList())
            }.collect {
                tree = it
                childrenByParent = indexByParent(it)
            }
        }
    }

    val hasIndex: Boolean get() = tree.isNotEmpty()

    val currentPath: String get() = pathSegments.joinToString("/")

    fun entries(): List<RepoEntryEntity> = childrenByParent[currentPath.ifEmpty { null }].orEmpty()

    fun childCount(dir: RepoEntryEntity): Int = childrenByParent[dir.path]?.size ?: 0

    /* ── 新建笔记（当前目录）────────────────────────────────── */

    var createInFlight by mutableStateOf(false)
        private set
    var createError by mutableStateOf<String?>(null)
        private set

    /**
     * 在当前目录新建空 Markdown 并打开 Editor（§写路径与 Editor 同一收敛语义：
     * 成功 → 异步 refreshTree 补齐整树；同名 → 内联错误；绝不覆盖已有文件）。
     */
    fun createNote(rawName: String, onCreated: (String) -> Unit) {
        val fileName = sanitizeNoteName(rawName)
        if (fileName == null) {
            createError = "名字不可用：不能为空，不含 / \\ :，也不以 . 开头"
            return
        }
        val folder = currentPath
        val path = if (folder.isEmpty()) fileName else "$folder/$fileName"
        if (createInFlight) return
        if (!AppGraph.network.isOnline) {
            createError = "当前离线，无法新建笔记"
            return
        }
        createInFlight = true
        createError = null
        viewModelScope.launch {
            when (val r = AppGraph.noteRepository.createNote(path, "")) {
                is NoteSaveResult.Saved -> {
                    createInFlight = false
                    // 写操作后远端 Tree 必变：绕过 freshness window 强制补齐整树（如新目录的 DIRECTORY 行）
                    AppGraph.appScope.launch { AppGraph.indexRepository.refreshTree(force = true) }
                    onCreated(path)
                }
                is NoteSaveResult.Conflict -> {
                    createInFlight = false
                    createError = "同名笔记已存在，换个名字吧"
                }
                is NoteSaveResult.Error -> {
                    createInFlight = false
                    createError = createWriteErrorMessage(
                        r.error,
                        offline = "当前离线，无法新建笔记",
                        fallback = "创建失败，请稍后再试",
                    )
                }
            }
        }
    }

    /** 关掉新建对话框时清掉上次的内联错误，重开不再展示陈旧报错。 */
    fun clearCreateError() {
        createError = null
    }

    fun openFolder(fullPath: String) { pathSegments = fullPath.split("/") }
    fun pop() { pathSegments = pathSegments.dropLast(1) }
    fun navigateTo(index: Int) { pathSegments = if (index < 0) emptyList() else pathSegments.take(index + 1) }
    fun reset() { pathSegments = emptyList() }
}

/** 目录浏览索引：按 parentPath 分组并预排序（文件夹在前 + 名称字典序），一次构建全目录复用。纯函数，可单测。 */
internal fun indexByParent(tree: List<RepoEntryEntity>): Map<String?, List<RepoEntryEntity>> =
    tree.groupBy { it.parentPath }
        .mapValues { (_, children) ->
            children.sortedWith(compareBy({ it.kind != EntryKind.DIRECTORY }, { it.name }))
        }

/** 文件浏览页（原型 renderFiles）：返回 + 面包屑 + 文件夹/文件/附件行 + 空文件夹态 + 新建笔记 */
@Composable
fun FilesScreen(
    onOpenNote: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onAttachmentTap: () -> Unit,
    onOpenEditor: (String) -> Unit,
    viewModel: FilesViewModel = viewModel(),
) {
    // tree 变化时若当前层级已不存在（如仓库刷新删除目录），回到根目录
    LaunchedEffect(viewModel.tree, viewModel.pathSegments) {
        if (viewModel.pathSegments.isNotEmpty()) {
            val current = viewModel.currentPath
            if (viewModel.tree.none { it.path == current }) viewModel.reset()
        }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    if (showCreateDialog) {
        CreateNoteDialog(
            folderLabel = viewModel.currentPath.ifEmpty { "根目录" },
            creating = viewModel.createInFlight,
            error = viewModel.createError,
            onCreate = { raw ->
                viewModel.createNote(raw) { path ->
                    showCreateDialog = false
                    onOpenEditor(path) // 创建成功直接进入编辑 —— 「随手记一笔」不打断
                }
            },
            onDismiss = {
                showCreateDialog = false
                viewModel.clearCreateError()
            },
        )
    }

    val entries = viewModel.entries()
    val browsingFolder = viewModel.pathSegments.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回（根路径隐藏）+ 面包屑 + 新建
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (browsingFolder) {
                AppIconButton(icon = AppIcons.Back, contentDescription = "返回上一层", onClick = { viewModel.pop() })
            }
            Breadcrumbs(
                path = viewModel.pathSegments,
                onNavigate = { viewModel.navigateTo(it) },
                modifier = Modifier.weight(1f),
            )
            AppIconButton(icon = AppIcons.Add, contentDescription = "新建笔记", onClick = { showCreateDialog = true })
        }

        // 列表走 LazyColumn（大文件夹只组合可见行）；空态保持原滚动容器
        if (viewModel.hasIndex && entries.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = AppSpacing.screenBottomPadding),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.path }) { index, entry ->
                    if (index > 0) AppHorizontalDivider()
                    when (entry.kind) {
                        EntryKind.DIRECTORY -> FolderRow(
                            entry = entry,
                            childCount = viewModel.childCount(entry),
                            onClick = { viewModel.openFolder(entry.path) },
                        )
                        EntryKind.MARKDOWN -> FileRow(entry = entry, onClick = { onOpenNote(entry.path) })
                        EntryKind.IMAGE -> AttachmentRow(entry = entry, onClick = { onOpenImage(entry.path) })
                        else -> AttachmentRow(entry = entry, onClick = onAttachmentTap)
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = AppSpacing.screenBottomPadding),
            ) {
                if (!viewModel.hasIndex) {
                    EmptyState(
                        illustration = R.drawable.spot_empty,
                        title = "还没有仓库索引",
                        subtitle = "完成引导或联网刷新一次，即可离线浏览全部目录",
                    )
                } else {
                    EmptyState(
                        illustration = R.drawable.spot_empty,
                        title = "这个文件夹是空的",
                        subtitle = "在电脑端往这里放点东西，刷新后就能看到",
                    )
                }
            }
        }
    }
}

/** 面包屑（原型 .crumbs：全部笔记 / 工作 / …，当前段加粗，长路径横向滚动） */
@Composable
private fun Breadcrumbs(path: List<String>, onNavigate: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            "全部笔记",
            style = AppTypography.bodySmall,
            color = if (path.isEmpty()) AppColors.textPrimary else AppColors.textTertiary,
            fontWeight = if (path.isEmpty()) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .clickable(onClick = { onNavigate(-1) })
                .padding(horizontal = 4.dp),
            maxLines = 1,
        )
        path.forEachIndexed { index, name ->
            Text("/", style = AppTypography.bodySmall, color = AppColors.borderStrong)
            val isCurrent = index == path.lastIndex
            Text(
                name,
                style = AppTypography.bodySmall,
                color = if (isCurrent) AppColors.textPrimary else AppColors.textTertiary,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clickable(onClick = { onNavigate(index) })
                    .padding(horizontal = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
