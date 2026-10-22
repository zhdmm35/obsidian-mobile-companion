package com.obsidiancompanion.feature.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.AppChip

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteTemplatePicker(selected: NoteTemplate, enabled: Boolean, onSelect: (NoteTemplate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("初始模板", style = AppTypography.caption, color = AppColors.textTertiary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NoteTemplate.entries.forEach { template ->
                AppChip(template.label, if (enabled) ({ onSelect(template) }) else null,
                    Modifier.heightIn(min = 48.dp), selected = template == selected)
            }
        }
    }
}

@Composable
fun NoteTemplatePreview(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("正文预览", style = AppTypography.caption, color = AppColors.textTertiary)
        Text(
            text.ifEmpty { "空白笔记，创建后开始书写。" },
            style = AppTypography.bodySmall,
            color = AppColors.textSecondary,
            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
        )
    }
}
