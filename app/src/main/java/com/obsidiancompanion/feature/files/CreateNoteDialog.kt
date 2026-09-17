package com.obsidiancompanion.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppTypography

/**
 * 新建笔记名称 → 合法文件名（null = 不可用）。
 * 规则：去首尾空白；不能为空；不含 `/` `\` `:`（防止逃逸出当前目录）；不以 `.` 开头（隐藏路径会被 Tree 过滤）；
 * 缺 `.md` 后缀自动补齐；去后缀后正文为空（如只输了 ".md"）不可用。
 */
fun sanitizeNoteName(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.any { it == '/' || it == '\\' || it == ':' }) return null
    if (trimmed.startsWith('.')) return null
    val withExt = if (trimmed.endsWith(".md", ignoreCase = true)) trimmed else "$trimmed.md"
    if (withExt.removeSuffix(".md").isBlank()) return null
    return withExt
}

/** 新建笔记对话框：名称输入 + 目标目录说明 + 内联错误；创建中禁用按钮防连点。 */
@Composable
fun CreateNoteDialog(
    folderLabel: String,
    creating: Boolean,
    error: String?,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        containerColor = AppColors.surface,
        shape = AppShapes.medium,
        title = { Text("新建笔记", style = AppTypography.bodyBase) },
        text = {
            Column {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = AppTypography.bodyBase.copy(color = AppColors.textPrimary),
                    cursorBrush = SolidColor(AppColors.accent),
                    singleLine = true,
                    enabled = !creating,
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(AppShapes.medium)
                                .background(AppColors.background)
                                .border(1.dp, if (error == null) AppColors.borderStrong else AppColors.danger, AppShapes.medium),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Box(Modifier.padding(horizontal = 12.dp)) {
                                if (name.text.isEmpty()) {
                                    Text("笔记名（自动补 .md）", style = AppTypography.bodyBase, color = AppColors.textMeta)
                                }
                                inner()
                            }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (error != null) error else "保存在：$folderLabel",
                    style = AppTypography.caption,
                    color = if (error != null) AppColors.danger else AppColors.textTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.text) }, enabled = !creating) {
                Text(if (creating) "创建中…" else "创建", color = AppColors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) {
                Text("取消", color = AppColors.textTertiary)
            }
        },
    )
}
