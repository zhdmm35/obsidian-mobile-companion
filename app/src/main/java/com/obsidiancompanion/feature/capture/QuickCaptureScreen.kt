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
import androidx.compose.ui.text.TextRange
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
import com.obsidiancompanion.feature.files.createWriteErrorMessage
import com.obsidiancompanion.feature.files.isWindowsHostileSegment
import com.obsidiancompanion.feature.files.sanitizeNoteName
import kotlinx.coroutines.launch

/** 分享内容预览的最大字符数（超出显示省略号；保存不受此限）。 */
private const val PREVIEW_MAX_CHARS = 4000

/**
 * 快速收集默认笔记名：取首条非空行，去掉 Markdown 装饰（# > - [ ] * ` 等），
 * 截 30 字；取不到 → 「快速收集」。
 * 保证返回值能过 [sanitizeNoteName]：裸链接取主机名，其余情况把文件名禁用字符
 * （/ \ :）折叠为空格、剥掉开头 '.'。纯函数，可单测。
 */
fun defaultCaptureTitle(text: String): String {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return "快速收集"
    val cleaned = firstLine
        .trim()
        .trimStart('#') // 标题记号（任意多个 #，一次剥掉）
        .trimStart()
        .removePrefix(">").trimStart()
        .removePrefix("- [ ]").removePrefix("- [x]").removePrefix("- [X]")
        .removePrefix("-").removePrefix("*").trimStart()
        .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1") // [文字](url) → 文字
        .replace(Regex("[*`_\\[\\]]"), "") // 行内强调/代码记号（不含 '#'：保留 C# 等正文）
        .trim()
    if (cleaned.isEmpty()) return "快速收集"
    // 裸链接（分享最常见的形态）：整行就是一个 URL 时取主机名做默认名（主机名不区分大小写，归一小写）
    val host = Regex("^https?://([^/\\s]+)\\S*$", RegexOption.IGNORE_CASE).matchEntire(cleaned)
        ?.groupValues?.get(1)
        ?.replace(Regex("^www\\.", RegexOption.IGNORE_CASE), "")
        ?.lowercase()
    if (!host.isNullOrEmpty() && !host.startsWith('.')) return host.take(30)
    // 其余情况：禁用字符折叠为空格、剥掉开头 '.'，保证预填名可用
    return cleaned
        .replace(Regex("[/\\\\:]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim().trimStart('.').trim()
        // take 可能把 emoji 的代理对切成孤立高位代理（UTF-8 编码后变 '?' 进文件名）——剥掉
        .take(30).dropLastWhile { it.isHighSurrogate() }.trim()
        .ifEmpty { "快速收集" }
}

/**
 * 收集目标文件夹合法化："" → 根目录（返回 ""）；允许嵌套 "a/b"；
 * 段为空 / 为 `.` `..` / 以 `.` 开头 / 含 `\` `:` / Windows 敌意字符与保留名 → null（不可用）。纯函数，可单测。
 */
fun sanitizeCaptureFolder(raw: String): String? {
    val trimmed = raw.trim().trim('/')
    if (trimmed.isEmpty()) return ""
    val segments = trimmed.split('/')
    if (segments.any { seg ->
            val s = seg.trim()
            s.isEmpty() || s == "." || s == ".." || s.startsWith('.') ||
                s.any { it == '\\' || it == ':' } || isWindowsHostileSegment(s)
        }
    ) {
        return null
    }
    return segments.joinToString("/") { it.trim() }
}

/** 快速收集（系统分享 → 新笔记）：预览 + 可改名/目录 + 保存。暂存文本在进入时消费（见 init）。 */
class QuickCaptureViewModel : ViewModel() {

    /** 进入时快照分享文本。 */
    var sharedText by mutableStateOf(AppGraph.pendingSharedText.value.orEmpty())
        private set

    // 预填标题光标置尾：用户直接打字是追加，而不是插到标题前面
    var nameField by mutableStateOf(
        defaultCaptureTitle(AppGraph.pendingSharedText.value.orEmpty())
            .let { TextFieldValue(it, TextRange(it.length)) },
    )
    var folderField by mutableStateOf(TextFieldValue(""))

    init {
        // 快照后立即消费暂存：系统返回/手势退出不会再被导航层重新弹出本页；
        // 本页打开期间收到的新分享留在暂存里，退出回到 tab 后由导航层接着打开。
        // （放在 VM init —— VM 随返回栈条目存活，旋转重建不会重复消费。）
        AppGraph.pendingSharedText.value = null
    }

    var inFlight by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    fun save(onSaved: (String) -> Unit) {
        if (inFlight) return
        // 进程被杀后返回栈恢复时暂存已空 —— 不允许把空内容存成笔记
        if (sharedText.isBlank()) {
            saveError = "分享内容已失效，请重新分享"
            return
        }
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
                    // 写操作后远端 Tree 必变：绕过 freshness window 强制补齐整树（如新目录的 DIRECTORY 行）
                    AppGraph.appScope.launch { AppGraph.indexRepository.refreshTree(force = true) }
                    onSaved(fileName)
                }
                is NoteSaveResult.Conflict -> {
                    inFlight = false
                    saveError = "同名笔记已存在，换个名字或文件夹吧"
                }
                is NoteSaveResult.Error -> {
                    inFlight = false
                    saveError = createWriteErrorMessage(
                        r.error,
                        offline = "当前离线，无法保存，联网后再试",
                        fallback = "保存失败，请稍后再试",
                    )
                }
            }
        }
    }
}

@Composable
fun QuickCaptureScreen(
    onLeave: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    viewModel: QuickCaptureViewModel = viewModel(),
) {
    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回（放弃本次收集；暂存文本进入时已消费，直接出栈即可）+ 标题
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
                onClick = onLeave,
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
                // 预览只取前若干字（大段分享不必整篇排版）；保存仍用完整 sharedText
                Text(
                    viewModel.sharedText.take(PREVIEW_MAX_CHARS)
                        + if (viewModel.sharedText.length > PREVIEW_MAX_CHARS) "\n…" else "",
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
