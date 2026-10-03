package com.obsidiancompanion.feature.home

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.AppHorizontalDivider
import com.obsidiancompanion.core.ui.AppChip
import com.obsidiancompanion.core.ui.SectionHeader
import com.obsidiancompanion.core.ui.fadeUp
import com.obsidiancompanion.feature.sync.SyncStatusChip

/**
 * 首页（原型 renderHome）：标题+状态 chip / 离线条 / 搜索入口 /
 * 最近打开卡片 / 草稿中心 / 最近修改与阅读切换 / 收藏 / 全部笔记入口。
 * 数据全部来自 Tree Cache + Room，离线可看（§24）。
 */
@Composable
fun HomeScreen(
    onOpenNote: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenDrafts: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // 所有仓库的编辑草稿与分享草稿总数。
    val draftCount by viewModel.draftCount.collectAsState()
    var showRecentRead by rememberSaveable { mutableStateOf(false) }
    val lastRead = state.recentRead.firstOrNull()

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

        Text(
            "随手收集，慢慢读懂。",
            style = AppTypography.bodySmall,
            color = AppColors.textTertiary,
            modifier = Modifier.padding(horizontal = AppSpacing.screenPaddingHorizontal),
        )

        if (state.isOffline) OfflineBanner()

        // 搜索入口：与搜索页同为 48dp，保留暖白底。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AppSpacing.screenPaddingHorizontal,
                    end = AppSpacing.screenPaddingHorizontal,
                    top = AppSpacing.sm,
                    bottom = AppSpacing.xs,
                )
                .heightIn(min = 48.dp)
                .clip(AppShapes.medium)
                .background(AppColors.surface)
                .clickable(role = Role.Button, onClick = onOpenSearch)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(AppIcons.Search, contentDescription = null, tint = AppColors.textMeta, modifier = Modifier.size(16.dp))
            Text("搜索笔记……", style = AppTypography.bodyBase, color = AppColors.textMeta)
        }

        if (lastRead != null) {
            RecentReadingCard(note = lastRead, onClick = { onOpenNote(lastRead.path) })
        }

        // 保留常驻入口；待处理内容用品牌色强调，本机草稿不会自动上传。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AppSpacing.screenPaddingHorizontal,
                    end = AppSpacing.screenPaddingHorizontal,
                    top = AppSpacing.md,
                )
                .clip(AppShapes.medium)
                .background(if (draftCount > 0) AppColors.calloutNoteBg else AppColors.surface)
                .border(1.dp, if (draftCount > 0) AppColors.calloutNoteBorder else AppColors.borderStrong, AppShapes.medium)
                .clickable(role = Role.Button, onClick = onOpenDrafts)
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(AppIcons.Share, contentDescription = null, tint = if (draftCount == 0) AppColors.textTertiary else AppColors.accent, modifier = Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text("草稿中心", style = AppTypography.rowTitleSmall)
                Text(
                    when {
                        draftCount < 0 -> "草稿读取失败，点击查看"
                        draftCount > 0 -> "$draftCount 条待处理 · 仅保存在本机"
                        else -> "分享文字到这里，留待整理"
                    },
                    style = AppTypography.caption,
                    color = AppColors.textTertiary,
                )
            }
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = AppColors.textMeta, modifier = Modifier.size(16.dp))
        }

        // 两组记录切换展示，避免同一篇笔记在首页连续出现；不改变记录本身。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = AppSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            AppChip("最近修改", { showRecentRead = false }, Modifier.heightIn(min = 48.dp), selected = !showRecentRead)
            AppChip("最近阅读", { showRecentRead = true }, Modifier.heightIn(min = 48.dp), selected = showRecentRead)
        }
        val recentNotes = if (showRecentRead) state.recentRead.drop(1) else state.recentModified
        when {
            state.loading -> HomeEmptyHint(AppIcons.Book, "正在整理笔记列表…")
            recentNotes.isEmpty() -> HomeEmptyHint(
                icon = if (showRecentRead) AppIcons.Book else AppIcons.Refresh,
                text = when {
                    !showRecentRead -> "暂无更新记录，电脑修改笔记后刷新即可查看"
                    lastRead != null -> "最近打开的笔记在上方，其他阅读记录会出现在这里"
                    else -> "打开一篇笔记，开始你的阅读记录"
                },
            )
            else -> DividerList(recentNotes) { note ->
                NoteListItem(note = note, onClick = { onOpenNote(note.path) }, large = true)
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

/** 最新阅读记录来自本机 metadata；再次打开沿用阅读器，不承诺恢复滚动位置。 */
@Composable
private fun RecentReadingCard(note: NoteRowUi, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenPaddingHorizontal)
            .padding(top = AppSpacing.md)
            .clip(AppShapes.large)
            .background(AppColors.surface)
            .border(1.dp, AppColors.borderStrong, AppShapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(AppSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Icon(AppIcons.Book, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(18.dp))
            Text("最近打开", style = AppTypography.caption, color = AppColors.textTertiary, modifier = Modifier.weight(1f))
            note.timeLabel?.let { Text(it, style = AppTypography.caption, color = AppColors.textMeta) }
        }
        Text(note.title, style = AppTypography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(note.folder, style = AppTypography.caption, color = AppColors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(
            Modifier.fillMaxWidth().padding(top = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Box(Modifier.size(width = 24.dp, height = 2.dp).background(AppColors.accent))
            Text("再次打开", style = AppTypography.bodyMedium, color = AppColors.calloutNoteTitle, modifier = Modifier.weight(1f))
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = AppColors.calloutNoteTitle, modifier = Modifier.size(18.dp))
        }
    }
}

/** 首页分区内的紧凑提示；长文字自然换行，保留完整说明。 */
@Composable
private fun HomeEmptyHint(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.textMeta, modifier = Modifier.size(18.dp))
        Text(text, style = AppTypography.caption, color = AppColors.textTertiary, modifier = Modifier.weight(1f))
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
