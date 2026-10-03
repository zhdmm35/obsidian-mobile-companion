package com.obsidiancompanion.feature.capture

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.lifecycle.SavedStateHandle
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
import com.obsidiancompanion.core.ui.AppHorizontalDivider
import com.obsidiancompanion.core.ui.PrimaryButton
import com.obsidiancompanion.data.repository.NoteSaveResult
import com.obsidiancompanion.feature.files.createWriteErrorMessage
import com.obsidiancompanion.feature.files.isWindowsHostileSegment
import com.obsidiancompanion.feature.files.sanitizeNoteName
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

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

/**
 * 快速收集（系统分享 → 新笔记）：预览 + 可改名/目录 + 保存。
 * 收到分享即落盘本机草稿（CaptureDraftStore，改名/目录修改同步更新）：
 * 离线可退出（联网后从首页「草稿中心」接着上传），杀进程后仍在；上传成功才清草稿。
 */
class QuickCaptureViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    private val draftId: String? = savedStateHandle["draftId"]
    var sharedText by mutableStateOf("")
        private set
    var nameField by mutableStateOf(TextFieldValue(""))
        private set
    var folderField by mutableStateOf(TextFieldValue(""))
        private set
    var targetRepo by mutableStateOf<String?>(null)
        private set

    /** 离线点击保存的提示（与错误语气区分）：内容已在本机，允许退出。 */
    var offlineNotice by mutableStateOf<String?>(null)
        private set

    var inFlight by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(true)
        private set
    private var pendingWrite: Job? = null
    private val loadJob = viewModelScope.launch {
        try {
            draftId?.let { AppGraph.captureDraft.find(it) }?.let { draft ->
                sharedText = draft.text
                nameField = TextFieldValue(draft.name, TextRange(draft.name.length))
                folderField = TextFieldValue(draft.folder)
            }
            targetRepo = AppGraph.settings.flow.first().repoId
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { saveError = "草稿读取失败，请返回后重试" }
        finally { loading = false }
    }

    fun onNameChange(value: TextFieldValue) {
        if (inFlight || loading) return
        val changed = nameField.text != value.text
        nameField = value
        if (changed) persistDraft()
    }

    fun onFolderChange(value: TextFieldValue) {
        if (inFlight || loading) return
        val changed = folderField.text != value.text
        folderField = value
        if (changed) persistDraft()
    }

    private fun persistDraft() {
        val id = draftId ?: return
        val name = nameField.text
        val folder = folderField.text
        pendingWrite?.cancel()
        // 合并快速输入；进程 scope 保证页面被导航销毁时已排队的写入仍可完成。
        pendingWrite = AppGraph.appScope.launch {
            delay(250)
            try { AppGraph.captureDraft.update(id, name, folder) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                withContext(Dispatchers.Main) { saveError = "改名或目录暂存失败，请重试" }
            }
        }
    }

    private suspend fun flushDraft() {
        loadJob.join()
        pendingWrite?.cancelAndJoin()
        val id = draftId ?: return
        AppGraph.captureDraft.update(id, nameField.text, folderField.text)
    }

    fun onBackground() {
        if (!loading && !inFlight) {
            val previous = pendingWrite
            previous?.cancel()
            val name = nameField.text
            val folder = folderField.text
            pendingWrite = AppGraph.appScope.launch {
                try {
                    previous?.join()
                    draftId?.let { AppGraph.captureDraft.update(it, name, folder) }
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { withContext(Dispatchers.Main) { saveError = "草稿暂存失败，请重试" } }
            }
        }
    }

    fun leave(onLeave: () -> Unit) {
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            try { flushDraft(); onLeave() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { saveError = "草稿保存失败，请稍后重试或先复制内容" }
            finally { inFlight = false }
        }
    }

    fun save(onSaved: (String) -> Unit) {
        if (inFlight || loading) return
        offlineNotice = null
        saveError = null
        if (sharedText.isBlank()) { saveError = "分享内容已失效，请返回草稿中心"; return }
        val fileName = sanitizeNoteName(nameField.text)
        if (fileName == null) { saveError = "名字不可用：不能为空，不含 / \\ :，也不以 . 开头"; return }
        val folder = sanitizeCaptureFolder(folderField.text)
        if (folder == null) { saveError = "文件夹路径不可用：用 / 分层，段名不以 . 开头"; return }
        if (targetRepo == null) { saveError = "请先连接 GitHub 仓库"; return }
        val path = if (folder.isEmpty()) fileName else "$folder/$fileName"
        inFlight = true
        viewModelScope.launch {
            try {
                flushDraft()
                val uploadedDraft = draftId?.let { AppGraph.captureDraft.find(it) }
                if (uploadedDraft == null) { saveError = "这条草稿已被删除，请返回草稿中心"; return@launch }
                if (!AppGraph.network.isOnline) {
                    offlineNotice = "当前离线 —— 内容已保存本机，联网后再点保存即可上传"
                    return@launch
                }
                when (val r = AppGraph.noteRepository.createNote(path, uploadedDraft.text)) {
                    is NoteSaveResult.Saved -> {
                        try {
                            if (!AppGraph.captureDraft.clearIfUnchanged(uploadedDraft)) {
                                saveError = "已上传到 GitHub，本机草稿发生变化，已保留供核对"
                                return@launch
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            saveError = "已上传到 GitHub，但本机草稿清理失败，请核对后从草稿中心丢弃"
                            return@launch
                        }
                        AppGraph.appScope.launch { AppGraph.indexRepository.refreshTree(force = true) }
                        onSaved(fileName)
                    }
                    is NoteSaveResult.Conflict -> saveError = "同名笔记已存在，换个名字或文件夹吧"
                    is NoteSaveResult.Error -> saveError = createWriteErrorMessage(r.error,
                        offline = "当前离线，内容已保存在本机，联网后再试", fallback = "保存失败，请稍后再试")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { saveError = "保存失败，草稿仍保留，请稍后再试" }
            finally { inFlight = false }
        }
    }

}

@Composable
fun QuickCaptureScreen(
    onLeave: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    viewModel: QuickCaptureViewModel = viewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler {
        if (viewModel.inFlight) onShowSnackbar("正在保存，请稍候") else viewModel.leave(onLeave)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        // 顶栏：返回后本机草稿仍保留，上传期间等待请求结束。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回：内容已在本机草稿，退出不丢 —— 首页「草稿中心」入口可接着处理
            AppIconButton(
                icon = AppIcons.Back,
                contentDescription = "返回",
                onClick = {
                    if (viewModel.inFlight) onShowSnackbar("正在保存，请稍候") else viewModel.leave(onLeave)
                },
            )
            Text("收集内容", style = AppTypography.appBarTitle)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenPaddingHorizontal)
                .padding(bottom = AppSpacing.screenBottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = AppSpacing.sm)
                    .clip(AppShapes.large)
                    .background(AppColors.calloutNoteBg)
                    .border(1.dp, AppColors.calloutNoteBorder, AppShapes.large)
                    .padding(AppSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Icon(AppIcons.Share, contentDescription = null, tint = AppColors.calloutNoteTitle, modifier = Modifier.size(18.dp))
                    Text(
                        when {
                            viewModel.loading -> "正在读取草稿…"
                            viewModel.sharedText.isEmpty() -> "尚未读取到内容"
                            else -> "内容已暂存本机"
                        },
                        style = AppTypography.bodyMedium,
                        color = AppColors.calloutNoteTitle,
                    )
                }
                Text("可以先返回，稍后从草稿中心继续整理。", style = AppTypography.caption, color = AppColors.textSecondary)
            }
            // 分享内容预览（只读）
            Text("内容预览", style = AppTypography.sectionTitle, modifier = Modifier.padding(top = AppSpacing.sm))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 200.dp)
                    .clip(AppShapes.medium)
                    .background(AppColors.surface)
                    .border(1.dp, AppColors.borderStrong, AppShapes.medium)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // 预览只取前若干字（大段分享不必整篇排版）；保存仍用完整 sharedText
                Text(
                    viewModel.sharedText.take(PREVIEW_MAX_CHARS)
                        + if (viewModel.sharedText.length > PREVIEW_MAX_CHARS) "\n…" else "",
                    style = AppTypography.bodySmall,
                    color = AppColors.textSecondary,
                )
            }
            if (viewModel.sharedText.length > PREVIEW_MAX_CHARS) {
                Text("预览仅显示部分内容，保存时会保留全文。", style = AppTypography.caption, color = AppColors.textTertiary)
            }

            Text("保存位置", style = AppTypography.sectionTitle, modifier = Modifier.padding(top = AppSpacing.sm))
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Icon(AppIcons.Folder, contentDescription = null, tint = AppColors.textTertiary, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Text(viewModel.targetRepo ?: "正在读取仓库配置…", style = AppTypography.caption, color = AppColors.textTertiary, modifier = Modifier.weight(1f))
            }

            Text("笔记名", style = AppTypography.caption, color = AppColors.textTertiary)
            CaptureInput(
                value = viewModel.nameField,
                onValueChange = viewModel::onNameChange,
                placeholder = "给这条收集起个名字",
                enabled = !viewModel.inFlight && !viewModel.loading,
            )

            Text("文件夹", style = AppTypography.caption, color = AppColors.textTertiary)
            CaptureInput(
                value = viewModel.folderField,
                onValueChange = viewModel::onFolderChange,
                placeholder = "如 Inbox 或 收集/网页",
                enabled = !viewModel.inFlight && !viewModel.loading,
            )
            Text("文件夹留空时保存在根目录，笔记名自动补 .md。", style = AppTypography.caption, color = AppColors.textTertiary)

            viewModel.saveError?.let {
                Text(it, style = AppTypography.caption, color = AppColors.danger)
            }
            viewModel.offlineNotice?.let {
                Text(it, style = AppTypography.caption, color = AppColors.textSecondary)
            }
        }
        AppHorizontalDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .background(AppColors.surface)
                .padding(horizontal = AppSpacing.screenPaddingHorizontal, vertical = AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            PrimaryButton(
                text = if (viewModel.inFlight) "保存中…" else "保存到 GitHub",
                onClick = {
                    viewModel.save { fileName ->
                        onShowSnackbar("已保存到 $fileName")
                        onLeave()
                    }
                },
                enabled = !viewModel.inFlight && !viewModel.loading,
                block = true,
            )
            Text("点击保存才会上传；返回会保留本机草稿。", style = AppTypography.caption, color = AppColors.textTertiary)
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
                    .heightIn(min = 48.dp)
                    .clip(AppShapes.medium)
                    .background(AppColors.surface)
                    .border(1.dp, AppColors.borderStrong, AppShapes.medium),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                    if (value.text.isEmpty()) {
                        Text(placeholder, style = AppTypography.bodyBase, color = AppColors.textMeta)
                    }
                    inner()
                }
            }
        },
    )
}
