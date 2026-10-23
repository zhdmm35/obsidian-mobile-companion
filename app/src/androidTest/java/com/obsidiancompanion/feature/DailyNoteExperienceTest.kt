package com.obsidiancompanion.feature

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.MainActivity
import com.obsidiancompanion.core.design.AppTheme
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.data.settings.AppSettings
import com.obsidiancompanion.feature.files.CreateNoteDialog
import com.obsidiancompanion.feature.files.NoteTemplate
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 全部笔记来自本机合成数据；已有日记入口不会请求真实 GitHub。 */
@RunWith(AndroidJUnit4::class)
class DailyNoteExperienceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val repoId = "codex-ui-tests/daily-note-experience"
    private val date = LocalDate.now()
    private val path = "日记/$date.md"
    private val sha = "dailyexperienceoriginal"
    private val original = "已有日记正文，不应被模板覆盖"
    private lateinit var prior: AppSettings
    private lateinit var priorDaily: AppSettings
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun seed() = runBlocking {
        prior = AppGraph.settings.flow.first()
        AppGraph.settings.setAutoRefresh(false)
        AppGraph.settings.setRepository("codex-ui-tests", "daily-note-experience", "main")
        priorDaily = AppGraph.settings.flow.first()
        AppGraph.settings.setDailyNotePreferences(repoId, "日记", "daily")
        AppGraph.settings.setOnboarded(true)
        AppGraph.database.repoEntryDao().insertAll(listOf(
            RepoEntryEntity(repoId, path, "$date.md", "日记", EntryKind.MARKDOWN, sha, original.toByteArray().size.toLong(), null),
        ))
        AppGraph.contentCache.put(sha, original.toByteArray())
    }

    @After fun restore() = runBlocking {
        scenario?.close()
        SystemClock.sleep(300)
        AppGraph.database.repoEntryDao().deleteByRepo(repoId)
        AppGraph.database.openHelper.writableDatabase.execSQL("DELETE FROM note_user_metadata WHERE repoId = ?", arrayOf(repoId))
        AppGraph.contentCache.delete(sha)
        AppGraph.settings.setDailyNotePreferences(repoId, priorDaily.dailyNoteFolder, priorDaily.dailyNoteTemplate)
        if (prior.owner != null && prior.repo != null && prior.defaultBranch != null) {
            AppGraph.settings.setRepository(prior.owner!!, prior.repo!!, prior.defaultBranch!!, prior.canWrite)
        } else AppGraph.settings.clearRepository()
        AppGraph.settings.setOnboarded(prior.isOnboarded)
        AppGraph.settings.setAutoRefresh(prior.autoRefresh)
    }

    @Test fun homeDailyEntryOpensExistingBodyAndRemembersTemplateWithoutReplacingIt() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("今日笔记")
        waitForText("目标：$path", scrollToFind = true)
        click("会议记录")
        screenshot("experience-daily-note.png")
        click("打开或创建")
        waitForText("更多") // Reader 正文已加载；等待导航时不能滚动目标页面。
        waitForText(original)
        runBlocking {
            assertEquals("meeting", AppGraph.settings.flow.first().dailyNoteTemplate)
            assertEquals("日记", AppGraph.settings.flow.first().dailyNoteFolder)
            assertEquals(sha, AppGraph.database.repoEntryDao().get(repoId, path)!!.blobSha)
            assertEquals(original, AppGraph.contentCache.get(sha)!!.toString(Charsets.UTF_8))
        }
    }

    @Test fun newNoteDialogOffersMeetingTemplateBeforeCreating() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        var createdName: String? = null
        var createdTemplate: NoteTemplate? = null
        var createdDate: LocalDate? = null
        scenario!!.onActivity { activity ->
            activity.setContent { AppTheme {
                CreateNoteDialog("日记", false, null, { name, template, selectedDate ->
                    createdName = name; createdTemplate = template; createdDate = selectedDate
                }, {})
            } }
        }
        val input = findNode(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable }
        waitForText("笔记名（自动补 .md）")
        val nameInput = input ?: findNode(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable }
        assertNotNull(nameInput)
        assertTrue(nameInput!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "设计讨论")
        }))
        waitForText("设计讨论")
        click("会议记录")
        screenshot("experience-note-template.png")
        click("创建")
        instrumentation.waitForIdleSync()
        assertEquals("设计讨论", createdName)
        assertEquals(NoteTemplate.MEETING, createdTemplate)
        assertTrue(createdTemplate!!.render(createdName!!, createdDate!!).contains("## 参会人员"))
    }

    @Test fun dailyPreferencesBelongToTheirRepository() = runBlocking {
        AppGraph.settings.setDailyNotePreferences(repoId, "Journal/Daily", "empty")
        AppGraph.settings.setRepository("codex-ui-tests", "other-daily-repo", "main")
        assertEquals("", AppGraph.settings.flow.first().dailyNoteFolder)
        assertEquals("daily", AppGraph.settings.flow.first().dailyNoteTemplate)
        AppGraph.settings.setRepository("codex-ui-tests", "daily-note-experience", "main")
        assertEquals("Journal/Daily", AppGraph.settings.flow.first().dailyNoteFolder)
        assertEquals("empty", AppGraph.settings.flow.first().dailyNoteTemplate)
    }

    private fun findNode(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (matches(node)) return node
        for (index in 0 until node.childCount) findNode(node.getChild(index), matches)?.let { return it }
        return null
    }

    private fun waitForText(text: String, scrollToFind: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val exact = findNode(root) { it.text?.toString() == text || it.contentDescription?.toString() == text }
            (exact ?: findNode(root) {
                it.text?.toString()?.contains(text) == true || it.contentDescription?.toString()?.contains(text) == true
            })?.let { return it }
            if (scrollToFind) {
                findNode(root) { it.isScrollable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            SystemClock.sleep(100)
        }
        screenshot("experience-daily-failure.png")
        val visible = mutableListOf<String>()
        findNode(instrumentation.uiAutomation.rootInActiveWindow) {
            if (!it.isPassword && !it.isEditable && it.isVisibleToUser) {
                (it.text ?: it.contentDescription)?.toString()?.let(visible::add)
            }
            false
        }
        error("UI text not found: $text; visible UI: ${visible.joinToString(" | ")}")
    }

    private fun click(text: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var node: AccessibilityNodeInfo? = waitForText(text, scrollToFind = true)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(100)
        }
        error("UI text is not clickable: $text")
    }

    private fun screenshot(name: String) {
        SystemClock.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
