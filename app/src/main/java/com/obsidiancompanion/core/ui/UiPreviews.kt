package com.obsidiancompanion.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppTheme

/** 通用组件视觉走查 */
@Preview(name = "通用组件", showBackground = true, backgroundColor = 0xFFF5F4ED, widthDp = 390)
@Composable
private fun ComponentsPreview() {
    AppTheme {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(text = "主按钮", onClick = {})
                SecondaryButton(text = "次按钮", onClick = {})
                GhostButton(text = "文字", onClick = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppChip(text = "未选中", onClick = {})
                AppChip(text = "选中", onClick = {}, selected = true)
                AppChip(text = "失败", onClick = {}, danger = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                AppSwitch(checked = true, onCheckedChange = {})
                AppSwitch(checked = false, onCheckedChange = {})
            }
            EmptyState(icon = AppIcons.Search, title = "按文件名搜索", subtitle = "输入关键词，实时过滤 Vault 中的全部笔记")
            SectionHeader("分区标题")
            KeyValueRow("上次同步", "今天 22:36")
            SyncProgressBar(progress = 0.62f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DotsIndicator(count = 5, current = 2)
            }
        }
    }
}
