package com.obsidiancompanion.feature.home

import androidx.lifecycle.ViewModelStore
import com.obsidiancompanion.data.repository.NoteCreationResult
import com.obsidiancompanion.data.settings.AppSettings
import com.obsidiancompanion.feature.files.NoteTemplate
import com.obsidiancompanion.model.DomainError
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DailyNoteViewModelTest {
    private val store = ViewModelStore()
    private val settings = MutableStateFlow(AppSettings("o", "r", "main", dailyNoteFolder = "日记", dailyNoteTemplate = "daily"))
    private var currentDate = LocalDate.of(2026, 10, 22)
    private var calls = 0
    private var path = ""
    private var content = ""
    private var targetRepo = ""
    private var preferences = emptyList<String>()
    private var refreshes = 0
    private var result: NoteCreationResult = NoteCreationResult.Ready("日记/2026-10-22.md", true)
    private var gate: CompletableDeferred<Unit>? = null

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private fun daily() = DailyNoteViewModel(settings, { p, text, repo ->
        calls++; path = p; content = text; targetRepo = repo
        gate?.await()
        result
    }, { repo, folder, template -> preferences = listOf(repo, folder, template) }, { refreshes++ }, { currentDate })
        .also { store.put("daily", it); it.prepare() }

    @Test fun newDailyNoteUsesPreviewDateEvenAfterMidnightAndRefreshes() = runTest {
        val daily = daily()
        runCurrent()
        val preview = daily.preview
        currentDate = currentDate.plusDays(1)
        var opened = false
        daily.open { _, created -> opened = created }
        runCurrent()
        assertTrue(opened)
        assertEquals("日记/2026-10-22.md", path)
        assertEquals(preview, content)
        assertEquals("o/r", targetRepo)
        assertEquals(listOf("o/r", "日记", "daily"), preferences)
        assertEquals(1, refreshes)
        assertFalse(daily.inFlight)
        daily.prepare()
        runCurrent()
        assertEquals("2026-10-23", daily.date.toString())
    }

    @Test fun existingNoteOpensReaderWithoutRefreshOrReplacingItsBody() = runTest {
        result = NoteCreationResult.Ready("日记/2026-10-22.md", false)
        val daily = daily()
        runCurrent()
        var existing = false
        daily.open { _, created -> existing = !created }
        runCurrent()
        assertTrue(existing)
        assertEquals(0, refreshes)
    }

    @Test fun invalidFolderAndSwitchedRepositoryDoNotSendWrites() = runTest {
        val daily = daily()
        runCurrent()
        daily.onFolderChange("../escape")
        daily.open { _, _ -> fail("must stay") }
        runCurrent()
        assertEquals(0, calls)
        assertNotNull(daily.error)
        daily.onFolderChange("日记")
        settings.value = AppSettings("other", "repo", "main")
        daily.open { _, _ -> fail("must stay") }
        runCurrent()
        assertEquals(0, calls)
        assertTrue(daily.error!!.contains("仓库已切换"))
        assertTrue(preferences.isEmpty())
    }

    @Test fun errorAllowsRetryAndRepeatedTapDoesNotDuplicateCreation() = runTest {
        val daily = daily()
        runCurrent()
        result = NoteCreationResult.Error(DomainError.NetworkUnavailable)
        daily.open { _, _ -> fail("must stay") }
        runCurrent()
        assertTrue(daily.error!!.contains("离线"))
        assertFalse(daily.inFlight)
        gate = CompletableDeferred()
        result = NoteCreationResult.Ready("日记/2026-10-22.md", true)
        daily.open { _, _ -> }
        runCurrent()
        daily.onTemplateChange(NoteTemplate.MEETING)
        daily.open { _, _ -> fail("duplicate") }
        assertEquals(2, calls)
        assertEquals(NoteTemplate.DAILY, daily.template)
        gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, refreshes)
    }
}
