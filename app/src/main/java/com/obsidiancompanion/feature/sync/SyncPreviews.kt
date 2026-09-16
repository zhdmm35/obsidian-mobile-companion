package com.obsidiancompanion.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppTheme
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.model.SyncStatus

/** 同步域组件走查：5 态 chip + ConflictScreen（纯回调屏） */
@Preview(name = "同步状态 5 态", showBackground = true, backgroundColor = 0xFFF5F4ED, widthDp = 390)
@Composable
private fun SyncStatusChipsPreview() {
    AppTheme {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyncStatus.entries.forEach { status ->
                SyncStatusChip(status = status, onClick = {})
            }
            Text("ConflictScreen 预览见下（固定 Tab 0）", style = AppTypography.caption)
        }
    }
}

@Preview(name = "冲突处理页", showBackground = true, backgroundColor = 0xFFF5F4ED, widthDp = 390, heightDp = 800)
@Composable
private fun ConflictScreenPreview() {
    AppTheme {
        // notePath = null → DEBUG 演示模式（ConflictDemo 数据）
        ConflictScreen(notePath = null, onBack = {}, onResolved = {}, onShowSnackbar = {})
    }
}
