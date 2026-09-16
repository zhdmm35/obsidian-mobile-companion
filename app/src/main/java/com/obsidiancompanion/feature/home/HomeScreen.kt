package com.obsidiancompanion.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.AppHorizontalDivider
import com.obsidiancompanion.core.ui.EmptyState
import com.obsidiancompanion.core.ui.SectionHeader
import com.obsidiancompanion.core.ui.fadeUp
import com.obsidiancompanion.feature.sync.SyncStatusChip

/**
 * 首页（原型 renderHome）：标题+状态 chip / 离线条 / 搜索入口 /
 * 最近修改（observedChangedAt，检测到远端变化）/ 最近阅读 / 收藏（空则隐藏）/ 全部笔记入口。
 * 数据全部来自 Tree Cache + Room，离线可看（§24）。
 */
@Composable
fun HomeScreen(
    onOpenNote: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSync: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = AppSpacing.screenBottomPadding),
    ) {
        // 头部：我的笔记 + SyncStatusChip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AppSpacing.screenPaddingHorizontal,
                    end = AppSpacing.screenPaddingHorizontal,
                    top = 14.dp,
                    bottom = AppSpacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("我的笔记", style = AppTypography.screenTitle, modifier = Modifier.weight(1f))
            SyncStatusChip(status = state.status, onClick = onOpenSync)
        }

        if (state.isOffline) OfflineBanner()

        // 搜索入口（原型 .sfield：46dp 假搜索框）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AppSpacing.screenPaddingHorizontal,
                    end = AppSpacing.screenPaddingHorizontal,
                    top = AppSpacing.sm,
                    bottom = AppSpacing.xs,
                )
                .height(46.dp)
                .clip(AppShapes.medium)
                .background(AppColors.surface)
                .clickable(onClick = onOpenSearch)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(AppIcons.Search, contentDescription = null, tint = AppColors.textMeta, modifier = Modifier.size(16.dp))
            Text("搜索笔记……", style = AppTypography.bodyBase, color = AppColors.textMeta)
        }

        // 最近修改 = 检测到的远端变化（§22/§23：首次索引后为空是自然状态，不伪造数据）
        SectionHeader("最近修改")
        if (state.recentModified.isEmpty()) {
            EmptyState(
                icon = AppIcons.Refresh,
                title = "还没有最近更新记录",
                subtitle = "在电脑上修改笔记并刷新后，更新会出现在这里",
            )
        } else {
            DividerList(state.recentModified) { note ->
                NoteListItem(note = note, onClick = { onOpenNote(note.path) }, large = true)
            }
        }

        // 最近阅读
        SectionHeader("最近阅读")
        if (state.recentRead.isEmpty()) {
            EmptyState(
                icon = AppIcons.Book,
                title = "还没有阅读记录",
                subtitle = "打开一篇笔记，就会出现在这里",
            )
        } else {
            DividerList(state.recentRead) { note ->
                NoteListItem(note = note, onClick = { onOpenNote(note.path) })
            }
        }

        // 收藏（空则整段隐藏）
        if (state.favorites.isNotEmpty()) {
            SectionHeader("收藏")
            DividerList(state.favorites) { note ->
                NoteListItem(note = note, onClick = { onOpenNote(note.path) }, showStar = true)
            }
        }

        Spacer(Modifier.height(10.dp))

        // 全部笔记入口（原型 .entry；显示 Tree Cache 中的 Markdown 总数）
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenFiles)
                    .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = 15.dp)
                    .fadeUp(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(AppIcons.Folder, contentDescription = null, tint = AppColors.textPrimary, modifier = Modifier.size(20.dp))
                Text(
                    if (state.totalNotes > 0) "全部笔记 · ${state.totalNotes}" else "全部笔记",
                    style = AppTypography.rowTitle,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("浏览", style = AppTypography.caption, color = AppColors.textTertiary)
                Icon(AppIcons.ChevronRight, contentDescription = null, tint = AppColors.textMeta, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 原型 .list：子项之间 1px 分隔线 */
@Composable
fun <T> DividerList(
    items: List<T>,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            if (index > 0) AppHorizontalDivider()
            itemContent(item)
        }
    }
}
