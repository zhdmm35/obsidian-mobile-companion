package com.obsidiancompanion.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.core.design.AppColors
import com.obsidiancompanion.core.design.AppIcons
import com.obsidiancompanion.core.design.AppShapes
import com.obsidiancompanion.core.design.AppSpacing
import com.obsidiancompanion.core.design.AppTypography
import com.obsidiancompanion.core.ui.AppIconButton
import com.obsidiancompanion.core.ui.PrimaryButton
import com.obsidiancompanion.data.repository.NoteSaveResult
import com.obsidiancompanion.feature.files.sanitizeNoteName
import com.obsidiancompanion.model.DomainError
import kotlinx.coroutines.launch

/**
 * 快速收集默认笔记名：取首条非空行，去掉 Markdown 装饰（# > - [ ] * ` 等），
 * 截 30 字；取不到 → 「快速收集」。纯函数，可单测。
 */
fun defaultCaptureTitle(text: String): String {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return "快速收集"
    val cleaned = firstLine
        .trim()
        .removePrefix("#".repeat(6)).removePrefix("#".repeat(5)).removePrefix("#".repeat(4))
        .removePrefix("#".repeat(3)).removePrefix("#".repeat(2)).removePrefix("#")
        .trimStart()
        .removePrefix(">").trimStart()
        .removePrefix("- [ ]").removePrefix("- [x]").removePrefix("- [X]")
        .removePrefix("-").removePrefix("*").trimStart()
        .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1") // [文字](url) → 文字
        .replace(Regex("[#*`_\\[\\]]"), "")
        .trim()
    if (cleaned.isEmpty()) return "快速收集"
    return cleaned.take(30).trim()
}

/**
 * 收集目标文件夹合法化："" → 根目录（返回 ""）；允许嵌套 "a/b"；
 * 段为空 / 为 `.` `..` / 以 `.` 开头 / 含 `\` `:` → null（不可用）。纯函数，可单测。
 */
fun sanitizeCaptureFolder(raw: String): String? {
    val trimmed = raw.trim().trim('/')
    if (trimmed.isEmpty()) return ""
    val segments = trimmed.split('/')
    if (segments.any { seg ->
            val s = seg.trim()
            s.isEmpty() || s == "." || s == ".." || s.startsWith('.') || s.any { it == '\\' || it == ':' }
        }
    ) {
        return null
    }
    return segments.joinToString("/") { it.trim() }
}

/** 快速收集（系统分享 → 新笔记）：预览 + 可改名/目录 + 保存；保存或取消都消费掉暂存文本。 */
class QuickCaptureViewModel : ViewModel() {

    /** 进入时快照分享文本（保存/取消前不清 AppGraph 暂存，防中途退出丢内容）。 */
    var sharedText by mutableStateOf(AppGraph.pendingSharedText.value.orEmpty())
        private set

    var nameField by mutableStateOf(TextFieldValue(defaultCaptureTitle(AppGraph.pendingSharedText.value.orEmpty())))
    var folderField by mutableStateOf(TextFieldValue(""))

    var inFlight by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    fun save(onSaved: (String) -> Unit) {
        if (inFlight) return
        val fileName = sanitizeNoteName(nameField.text)
        if (fileName == null) {
            saveError = "名字不可用：不能为空，不含 / \\ :，也不以 . 开头"
            return
        }
        val folder = sanitizeCaptureFolder(folderField.text)
        if (folder == null) {
            saveError = "文件夹路径不可用：用 / 分层，段名不以 . 开头"
            return
        }
        if (!AppGraph.network.isOnline) {
            saveError = "当前离线，无法保存，联网后再试"
            return
        }
        val path = if (folder.isEmpty()) fileName else "$folder/$fileName"
        inFlight = true
        saveError = null
        viewModelScope.launch {
            when (val r = AppGraph.noteRepository.createNote(path, sharedText)) {
                is NoteSaveResult.Saved -> {
                    inFlight = false
                    AppGraph.pendingSharedText.value = null
                    AppGraph.appScope.launch { AppGraph.indexRepository.refreshTree() }
                    onSaved(fileName)
                }
                is NoteSaveResult.Conflict -> {
                    inFlight = false
                    saveError = "同名笔记已存在，换个名字或文件夹吧"
                }
                is NoteSaveResult.Error -> {
                    inFlight = false
                    saveError = when (r.error) {
                        DomainError.Unauthorized, DomainError.Forbidden -> "Token 没有写入权限，请在设置中重新设置"
                        DomainError.NetworkUnavailable -> "当前离线，无法保存，联网后再试"
                        DomainError.RateLimited -> "GitHub 接口限流，请稍后再试"
                        else -> "保存失败，请稍后再试"
                    }
                }
            }
        }
    }

    /** 取消/离开：消费暂存文本（用户明确不要了）。 */
    fun discard() {
        AppGraph.pendingSharedText.value = null
    }
}

@Composable
fun QuickCaptureScreen(
    onLeave: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    viewModel: QuickCaptureViewModel = viewModel(),
) {
    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回（= 丢弃）+ 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconButton(
                icon = AppIcons.Back,
                contentDescription = "返回",
                onClick = {
                    viewModel.discard()
                    onLeave()
                },
            )
            Text("保存分享内容", style = AppTypography.appBarTitle)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenPaddingHorizontal)
                .padding(bottom = AppSpacing.screenBottomPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 分享内容预览（只读）
            Text("内容", style = AppTypography.caption, color = AppColors.textTertiary)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .clip(AppShapes.medium)
                    .background(AppColors.surface)
                    .border(1.dp, AppColors.borderStrong, AppShapes.medium)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    viewModel.sharedText,
                    style = AppTypography.codeInline,
                    color = AppColors.textSecondary,
                )
            }

            Text("笔记名", style = AppTypography.caption, color = AppColors.textTertiary)
            CaptureInput(
                value = viewModel.nameField,
                onValueChange = { viewModel.nameField = it },
                placeholder = "笔记名（自动补 .md）",
                enabled = !viewModel.inFlight,
            )

            Text("文件夹", style = AppTypography.caption, color = AppColors.textTertiary)
            CaptureInput(
                value = viewModel.folderField,
                onValueChange = { viewModel.folderField = it },
                placeholder = "留空保存在根目录，如 Inbox 或 收集/网页",
                enabled = !viewModel.inFlight,
            )

            viewModel.saveError?.let {
                Text(it, style = AppTypography.caption, color = AppColors.danger)
            }

            Spacer(Modifier.height(6.dp))
            PrimaryButton(
                text = if (viewModel.inFlight) "保存中…" else "保存",
                onClick = {
                    viewModel.save { fileName ->
                        onShowSnackbar("已保存到 $fileName")
                        onLeave()
                    }
                },
                enabled = !viewModel.inFlight,
                block = true,
            )
        }
    }
}

@Composable
private fun CaptureInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    enabled: Boolean,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = AppTypography.bodyBase.copy(color = AppColors.textPrimary),
        cursorBrush = SolidColor(AppColors.accent),
        singleLine = true,
        enabled = enabled,
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(AppShapes.medium)
                    .background(AppColors.surface)
                    .border(1.dp, AppColors.borderStrong, AppShapes.medium),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.padding(horizontal = 12.dp)) {
                    if (value.text.isEmpty()) {
                        Text(placeholder, style = AppTypography.bodyBase, color = AppColors.textMeta)
                    }
                    inner()
                }
            }
        },
    )
}
