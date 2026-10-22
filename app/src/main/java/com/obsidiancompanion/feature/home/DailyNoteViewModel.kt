package com.obsidiancompanion.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidiancompanion.data.repository.NoteCreationResult
import com.obsidiancompanion.data.settings.AppSettings
import com.obsidiancompanion.feature.capture.sanitizeCaptureFolder
import com.obsidiancompanion.feature.files.NoteTemplate
import com.obsidiancompanion.feature.files.createWriteErrorMessage
import com.obsidiancompanion.feature.files.dailyNotePath
import com.obsidiancompanion.model.DomainError
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DailyNoteViewModel(
    private val settings: Flow<AppSettings>,
    private val openOrCreate: suspend (String, String, String) -> NoteCreationResult,
    private val rememberPreferences: suspend (String, String, String) -> Unit,
    private val refreshAfterCreate: () -> Unit,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {
    var date by mutableStateOf(LocalDate.MIN)
        private set
    var folder by mutableStateOf("")
        private set
    var template by mutableStateOf(NoteTemplate.DAILY)
        private set
    var repoId by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var inFlight by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val path: String? get() = sanitizeCaptureFolder(folder)?.let { dailyNotePath(it, date) }
    val preview: String get() = template.render(date.toString(), date)
    private var preparation: Job? = null
    private var preparationVersion = 0

    /** 每次打开重新读取设备本地日期；确认时沿用展示的日期，跨午夜也不会悄悄换目标。 */
    fun prepare() {
        if (inFlight) return
        val version = ++preparationVersion
        preparation?.cancel()
        date = today()
        loading = true
        error = null
        repoId = null
        preparation = viewModelScope.launch {
            try {
                val current = settings.first()
                repoId = current.repoId
                folder = current.dailyNoteFolder
                template = NoteTemplate.fromId(current.dailyNoteTemplate)
                if (repoId == null) error = "请先连接 GitHub 仓库"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "日记设置读取失败，请关闭后重试" }
            finally { if (version == preparationVersion) loading = false }
        }
    }

    fun onFolderChange(value: String) { if (!loading && !inFlight) folder = value }
    fun onTemplateChange(value: NoteTemplate) { if (!loading && !inFlight) template = value }

    fun open(onReady: (String, Boolean) -> Unit) {
        if (loading || inFlight) return
        val repo = repoId ?: return
        val normalized = sanitizeCaptureFolder(folder)
        if (normalized == null) { error = "文件夹路径不可用：用 / 分层，段名不以 . 开头"; return }
        val target = dailyNotePath(normalized, date)
        val content = preview
        val selectedTemplate = template.id
        inFlight = true
        error = null
        viewModelScope.launch {
            try {
                if (settings.first().repoId != repo) {
                    error = "仓库已切换，请关闭后重新打开，核对日记位置"
                    return@launch
                }
                rememberPreferences(repo, normalized, selectedTemplate)
                when (val result = openOrCreate(target, content, repo)) {
                    is NoteCreationResult.Ready -> {
                        if (result.created) refreshAfterCreate()
                        onReady(result.path, result.created)
                    }
                    is NoteCreationResult.Error -> error = if (result.error == DomainError.Conflict) {
                        "此位置已有同名文件夹或其他文件，请更换日记目录"
                    } else createWriteErrorMessage(result.error,
                        offline = "当前离线，无法新建今日笔记；已缓存的今日笔记可以打开",
                        fallback = "今日笔记未能打开，请稍后重试")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "今日笔记操作未完成，请重试" }
            finally { inFlight = false }
        }
    }
}
