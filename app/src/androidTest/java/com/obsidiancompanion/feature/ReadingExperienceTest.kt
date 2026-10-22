package com.obsidiancompanion.feature

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.obsidiancompanion.AppGraph
import com.obsidiancompanion.MainActivity
import com.obsidiancompanion.core.design.AppTheme
import com.obsidiancompanion.data.mock.ConflictDemo
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import com.obsidiancompanion.data.settings.AppSettings
import com.obsidiancompanion.data.settings.ReadingAppearance
import com.obsidiancompanion.feature.sync.ConflictScreen
import com.obsidiancompanion.feature.search.SearchScreen
import com.obsidiancompanion.feature.search.SearchViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 使用平台无障碍节点验证真实导航和重新打开；全部内容为本机合成数据，不请求远端。 */
@RunWith(AndroidJUnit4::class)
class ReadingExperienceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val repoId = "codex-ui-tests/reading-experience"
    private val path = "Inbox/Reading.md"
    private val sha = "readingexperiencecachedversion"
    private val taskSha = "readingexperiencetaskversion"
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var prior: AppSettings

    @Before
    fun seed() = runBlocking {
        prior = AppGraph.settings.flow.first()
        AppGraph.settings.setAutoRefresh(false)
        AppGraph.settings.setRepository("codex-ui-tests", "reading-experience", "main")
        AppGraph.settings.setOnboarded(true)
        AppGraph.database.repoEntryDao().insertAll(listOf(
            RepoEntryEntity(repoId, "Inbox", "Inbox", null, EntryKind.DIRECTORY, "folderversion", null, null),
            RepoEntryEntity(repoId, path, "Reading.md", "Inbox", EntryKind.MARKDOWN, sha, 5000L, null),
            RepoEntryEntity(repoId, "Inbox/Uncached.md", "Uncached.md", "Inbox", EntryKind.MARKDOWN, "readingexperienceuncachedversion", 10L, null),
        ))
        val text = "# Reading\n\n" + (0..99).joinToString("\n\n") { "Paragraph $it: reading position test content." }
        AppGraph.contentCache.put(sha, text.toByteArray())
        AppGraph.database.noteMetadataDao().markRead(repoId, path, System.currentTimeMillis())
        AppGraph.database.noteMetadataDao().saveReadingPosition(repoId, path, sha, 30, 10)
    }

    @After
    fun restore() = runBlocking {
        scenario?.close()
        // 阅读位置在 appScope 写入，等待最后一次写入，再删除本测试作用域。
        SystemClock.sleep(300)
        AppGraph.database.repoEntryDao().deleteByRepo(repoId)
        AppGraph.database.pendingEditDao().delete(repoId, path)
        AppGraph.database.openHelper.writableDatabase.execSQL("DELETE FROM note_user_metadata WHERE repoId = ?", arrayOf(repoId))
        AppGraph.contentCache.delete(sha)
        AppGraph.contentCache.delete(taskSha)
        AppGraph.contentCache.delete("readingexperienceuncachedversion")
        if (prior.owner != null && prior.repo != null && prior.defaultBranch != null) {
            AppGraph.settings.setRepository(prior.owner!!, prior.repo!!, prior.defaultBranch!!, prior.canWrite)
        } else AppGraph.settings.clearRepository()
        AppGraph.settings.setOnboarded(prior.isOnboarded)
        AppGraph.settings.setAutoRefresh(prior.autoRefresh)
        AppGraph.settings.setReadingAppearance(prior.readingAppearance)
    }

    @Test
    fun searchReportsCachedCoverageAndLinksToFolderDownloads() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("搜索笔记……")
        waitForText("正文可搜索 1 / 2 篇")
        screenshot("experience-search.png")
        click("管理离线下载")
        waitForText("Inbox")
    }

    @Test
    fun refreshingCoverageRescansNewlyCachedNotesWithoutChangingTheQuery() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var search: SearchViewModel
        scenario!!.onActivity { activity ->
            search = ViewModelProvider(activity)[SearchViewModel::class.java]
            activity.setContent {
                AppTheme { SearchScreen(onBack = {}, onOpenNote = {}, onManageOffline = {}, viewModel = search) }
            }
        }
        waitForText("正文可搜索 1 / 2 篇")
        scenario!!.onActivity { search.onQueryChange(TextFieldValue("downloadkeyword")) }
        waitForText("没有找到「downloadkeyword」相关的笔记")
        runBlocking {
            AppGraph.contentCache.put("readingexperienceuncachedversion", "new downloadkeyword content".toByteArray())
        }
        scenario!!.onActivity { search.refreshCoverage() }
        waitForText("正文可搜索 2 / 2 篇")
        waitForText("Uncached")
    }

    @Test
    fun reopeningNoteRestoresPersistedPositionAfterActivityIsClosed() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("Reading")
        waitForText("Paragraph 28: reading position test content.")
        screenshot("experience-reading.png")
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("Reading")
        waitForText("Paragraph 28: reading position test content.")
        runBlocking {
            val metadata = AppGraph.database.noteMetadataDao().get(repoId, path)
            assertNotNull(metadata)
            assertTrue(metadata!!.readingItemIndex >= 30)
        }
    }

    @Test
    fun readingAppearancePersistsAcrossReopenAndCanBeReset() {
        runBlocking { AppGraph.settings.setReadingAppearance(ReadingAppearance()) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("Reading")
        click("更多")
        click("阅读设置")
        repeat(3) { click("增大字号") }
        waitForText("字号 20")
        click("宽松")
        waitUntil { runBlocking { AppGraph.settings.flow.first().readingAppearance == ReadingAppearance(20, 115) } }
        SystemClock.sleep(400)
        screenshot("experience-appearance.png")
        click("完成")
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("Reading")
        click("更多")
        click("阅读设置")
        waitForText("字号 20")
        click("恢复默认")
        waitForText("字号 17")
        waitUntil { runBlocking { AppGraph.settings.flow.first().readingAppearance == ReadingAppearance() } }
    }

    @Test
    fun taskEntryTogglesNestedTasksAndRetainsExactDraftAfterLeaving() {
        val source = "---\r\ntitle: tasks\r\n---\r\n# Reading\r\n\r\n- [ ] 检查同步\r\n\r\n> - [ ] 更新说明\r\n"
        val expected = source.replace("[ ]", "[x]")
        seedTaskNote(source)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("Reading")
        click("更多")
        click("勾选任务")
        waitForText("可勾选任务，完成后点「保存」同步。")
        waitUntil { waitForText("任务：检查同步").isCheckable }
        assertFalse(waitForText("任务：检查同步").isChecked)
        click("任务：检查同步")
        waitUntil { waitForText("任务：检查同步").isChecked }
        click("任务：更新说明")
        waitUntil { waitForText("任务：更新说明").isChecked }
        SystemClock.sleep(400)
        screenshot("experience-tasks.png")
        click("返回编辑")
        // TextField 的语义可能规范化换行；逐字验证交给持久化草稿。
        waitForText("[x] 检查同步")
        click("取消")
        click("保留草稿并退出")
        waitUntil { runBlocking { AppGraph.database.pendingEditDao().get(repoId, path)?.content == expected } }
        assertEquals(expected, runBlocking { AppGraph.database.pendingEditDao().get(repoId, path)!!.content })
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("Reading")
        click("更多")
        click("勾选任务")
        click("恢复")
        waitUntil { waitForText("任务：检查同步").isChecked && waitForText("任务：更新说明").isChecked }
    }

    @Test
    fun readOnlyRepositoryBlocksTaskEditor() {
        runBlocking {
            AppGraph.settings.setRepository("codex-ui-tests", "reading-experience", "main", canWrite = false)
        }
        seedTaskNote("- [ ] 只读任务")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("Reading")
        click("更多")
        click("勾选任务")
        waitForText("当前 Token 只有只读权限")
    }

    @Test
    fun conflictShowsDifferencesAndBothCompleteVersions() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity ->
            activity.setContent {
                AppTheme {
                    ConflictScreen(notePath = null, onBack = {}, onResolved = {}, onShowSnackbar = {})
                }
            }
        }
        waitForText("− GitHub 版本中的行    + 我的修改中的行")
        screenshot("experience-conflict.png")
        click("我的修改")
        waitForText(ConflictDemo.LOCAL)
        click("GitHub 版本")
        waitForText(ConflictDemo.REMOTE)
    }

    private fun waitForText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            (findText(root, text, exact = true) ?: findText(root, text, exact = false))?.let { return it }
            SystemClock.sleep(100)
        }
        screenshot("experience-failure.png")
        error("UI text not found: $text")
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        screenshot("experience-failure.png")
        error("Timed out waiting for reading experience state")
    }

    private fun seedTaskNote(source: String) = runBlocking {
        AppGraph.contentCache.put(taskSha, source.toByteArray())
        AppGraph.database.repoEntryDao().insertAll(listOf(
            RepoEntryEntity(repoId, path, "Reading.md", "Inbox", EntryKind.MARKDOWN, taskSha, source.length.toLong(), null),
        ))
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String, exact: Boolean): AccessibilityNodeInfo? {
        node ?: return null
        fun matches(value: CharSequence?) = if (exact) value?.toString() == text else value?.toString()?.contains(text) == true
        if (matches(node.text) || matches(node.contentDescription)) return node
        for (index in 0 until node.childCount) findText(node.getChild(index), text, exact)?.let { return it }
        return null
    }

    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = waitForText(text)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "UI text is not clickable: $text" }
    }

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
