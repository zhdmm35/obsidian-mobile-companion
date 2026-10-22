package com.obsidiancompanion.feature

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.MainActivity
import com.obsidiancompanion.core.design.AppTheme
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.data.settings.AppSettings
import com.obsidiancompanion.feature.capture.QuickCaptureScreen
import com.obsidiancompanion.feature.capture.QuickCaptureViewModel
import com.obsidiancompanion.feature.capture.defaultCaptureTitle
import com.obsidiancompanion.feature.editor.EditorScreen
import com.obsidiancompanion.feature.editor.EditorViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DraftExperienceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val repoId = "codex-ui-tests/draft-experience"
    private val path = "Draft.md"
    private val sha = "draftexperienceoriginal"
    private lateinit var prior: AppSettings
    private var priorFolder = ""
    private var scenario: ActivityScenario<MainActivity>? = null
    private val draftIds = mutableListOf<String>()

    @Before fun seed() = runBlocking {
        prior = AppGraph.settings.flow.first()
        AppGraph.settings.setAutoRefresh(false)
        AppGraph.settings.setRepository("codex-ui-tests", "draft-experience", "main")
        priorFolder = AppGraph.settings.flow.first().captureFolder
        AppGraph.settings.setCaptureFolder(repoId, "收集/网页")
        AppGraph.settings.setOnboarded(true)
        AppGraph.database.repoEntryDao().insertAll(listOf(
            RepoEntryEntity(repoId, path, path, null, EntryKind.MARKDOWN, sha, 4L, null),
        ))
        AppGraph.contentCache.put(sha, "base".toByteArray())
    }

    @After fun restore() = runBlocking {
        scenario?.close()
        SystemClock.sleep(300)
        draftIds.forEach { id -> AppGraph.captureDraft.find(id)?.let { AppGraph.captureDraft.clearIfUnchanged(it) } }
        AppGraph.database.pendingEditDao().delete(repoId, path)
        AppGraph.database.repoEntryDao().deleteByRepo(repoId)
        AppGraph.contentCache.delete(sha)
        AppGraph.settings.setCaptureFolder(repoId, priorFolder)
        if (prior.owner != null && prior.repo != null && prior.defaultBranch != null) {
            AppGraph.settings.setRepository(prior.owner!!, prior.repo!!, prior.defaultBranch!!, prior.canWrite)
        } else AppGraph.settings.clearRepository()
        AppGraph.settings.setOnboarded(prior.isOnboarded)
        AppGraph.settings.setAutoRefresh(prior.autoRefresh)
    }

    @Test fun stoppingActivityFlushesLatestEditorInput() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var editor: EditorViewModel
        scenario!!.onActivity { activity ->
            editor = ViewModelProvider(activity, AppGraph.editorViewModelFactory)[EditorViewModel::class.java]
            activity.setContent { AppTheme { EditorScreen(path, onLeave = {}, onOpenConflict = {}, onOpenToken = {}, onShowSnackbar = {}, viewModel = editor) } }
        }
        waitUntil { var loaded = false; scenario!!.onActivity { loaded = editor.loaded }; loaded }
        scenario!!.onActivity { editor.onValueChange(TextFieldValue("typed immediately before background")) }
        scenario!!.moveToState(Lifecycle.State.CREATED)
        waitUntil { runBlocking { AppGraph.database.pendingEditDao().get(repoId, path)?.content == "typed immediately before background" } }
    }

    @Test fun newShareUsesRememberedFolderAndAppendShowsExactDestination() {
        val sharedText = "Capture ${java.util.UUID.randomUUID()}"
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, sharedText)
        scenario = ActivityScenario.launch(intent)
        val title = defaultCaptureTitle(sharedText)
        waitUntil { runBlocking { AppGraph.captureDraft.drafts.first().any { it.name == title } } }
        val draft = runBlocking { AppGraph.captureDraft.drafts.first().first { it.name == title } }
        draftIds += draft.id
        // 小屏上目录输入框可能在折叠区下方；验证落盘目录及可见的完整目标确认，不依赖屏高。
        waitForText("内容已暂存本机")
        assertEquals("收集/网页", draft.folder)
        screenshot("experience-capture.png")
        click("追加到 Inbox.md")
        waitForText("收集/网页/Inbox.md")
        screenshot("experience-capture-confirm.png")
        click("取消")
        assertNotNull(runBlocking { AppGraph.captureDraft.find(draft.id) })
    }

    @Test fun existingRootDraftIsNotMovedAndBackgroundRemembersFolderPerRepo() {
        val draft = runBlocking { AppGraph.captureDraft.create("share", "existing", "") }
        draftIds += draft.id
        scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var capture: QuickCaptureViewModel
        scenario!!.onActivity { activity ->
            val factory = viewModelFactory { initializer { QuickCaptureViewModel(SavedStateHandle(mapOf("draftId" to draft.id))) } }
            capture = ViewModelProvider(activity, factory)[QuickCaptureViewModel::class.java]
            activity.setContent { AppTheme { QuickCaptureScreen(onLeave = {}, onShowSnackbar = {}, viewModel = capture) } }
        }
        waitUntil { var loaded = false; scenario!!.onActivity { loaded = !capture.loading }; loaded }
        scenario!!.onActivity {
            assertEquals("", capture.folderField.text)
            capture.onFolderChange(TextFieldValue(" 新目录 / 网页 "))
        }
        scenario!!.moveToState(Lifecycle.State.CREATED)
        waitUntil { runBlocking { AppGraph.settings.flow.first().captureFolder == "新目录/网页" } }
        assertEquals(" 新目录 / 网页 ", runBlocking { AppGraph.captureDraft.find(draft.id)!!.folder })
        runBlocking {
            AppGraph.settings.setRepository("codex-ui-tests", "other-capture-repo", "main")
            assertEquals("", AppGraph.settings.flow.first().captureFolder)
            AppGraph.settings.setRepository("codex-ui-tests", "draft-experience", "main")
            assertEquals("新目录/网页", AppGraph.settings.flow.first().captureFolder)
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("Timed out waiting for draft state")
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true) return node
        for (index in 0 until node.childCount) findText(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun waitForText(text: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        waitUntil { node = findText(instrumentation.uiAutomation.rootInActiveWindow, text); node != null }
        return node!!
    }

    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = waitForText(text)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "UI text is not clickable: $text" }
    }

    private fun screenshot(name: String) {
        SystemClock.sleep(400) // 无障碍节点先于对话框入场动画就绪，等待最终画面。
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
