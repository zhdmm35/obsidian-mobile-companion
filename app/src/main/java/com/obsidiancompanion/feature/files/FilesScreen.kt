package com.obsidiancompanion.feature.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.AppIconButton
import com.obsidiancompanion.core.ui.EmptyState
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.feature.home.DividerList
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

    init {
        viewModelScope.launch {
            AppGraph.settings.flow.flatMapLatest { s ->
                s.repoId?.let { AppGraph.indexRepository.observeTree(it) } ?: flowOf(emptyList())
            }.collect { tree = it }
        }
    }

    val hasIndex: Boolean get() = tree.isNotEmpty()

    val currentPath: String get() = pathSegments.joinToString("/")

    fun entries(): List<RepoEntryEntity> {
        val parent = currentPath.ifEmpty { null }
        return tree
            .filter { it.parentPath == parent }
            .sortedWith(compareBy({ it.kind != EntryKind.DIRECTORY }, { it.name }))
    }

    fun childCount(dir: RepoEntryEntity): Int = tree.count { it.parentPath == dir.path }

    fun openFolder(fullPath: String) { pathSegments = fullPath.split("/") }
    fun pop() { pathSegments = pathSegments.dropLast(1) }
    fun navigateTo(index: Int) { pathSegments = if (index < 0) emptyList() else pathSegments.take(index + 1) }
    fun reset() { pathSegments = emptyList() }
}

/** 文件浏览页（原型 renderFiles）：返回 + 面包屑 + 文件夹/文件/附件行 + 空文件夹态 */
@Composable
fun FilesScreen(
    onOpenNote: (String) -> Unit,
    onAttachmentTap: () -> Unit,
    viewModel: FilesViewModel = viewModel(),
) {
    // tree 变化时若当前层级已不存在（如仓库刷新删除目录），回到根目录
    LaunchedEffect(viewModel.tree, viewModel.pathSegments) {
        if (viewModel.pathSegments.isNotEmpty()) {
            val current = viewModel.currentPath
            if (viewModel.tree.none { it.path == current }) viewModel.reset()
        }
    }

    val entries = viewModel.entries()
    val browsingFolder = viewModel.pathSegments.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回（根路径隐藏）+ 面包屑
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
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppSpacing.screenBottomPadding),
        ) {
            when {
                !viewModel.hasIndex -> EmptyState(
                    icon = AppIcons.Folder,
                    title = "还没有仓库索引",
                    subtitle = "完成引导或联网刷新一次，即可离线浏览全部目录",
                )
                entries.isEmpty() -> EmptyState(
                    icon = AppIcons.Folder,
                    title = "这个文件夹是空的",
                    subtitle = "在电脑端往这里放点东西，刷新后就能看到",
                )
                else -> DividerList(entries) { entry ->
                    when (entry.kind) {
                        EntryKind.DIRECTORY -> FolderRow(
                            entry = entry,
                            childCount = viewModel.childCount(entry),
                            onClick = { viewModel.openFolder(entry.path) },
                        )
                        EntryKind.MARKDOWN -> FileRow(entry = entry, onClick = { onOpenNote(entry.path) })
                        else -> AttachmentRow(entry = entry, onClick = onAttachmentTap)
                    }
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
