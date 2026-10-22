package com.obsidiancompanion.feature.files

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class NoteTemplateTest {
    private val date = LocalDate.of(2026, 10, 22)

    @Test fun emptyTemplateKeepsExistingBlankNoteBehavior() {
        assertEquals("", NoteTemplate.EMPTY.render("标题", date))
    }

    @Test fun templatesUseTheGivenTitleAndLocalDateWithoutUnresolvedPlaceholders() {
        val daily = NoteTemplate.DAILY.render("今日记录", date)
        assertTrue(daily.startsWith("# 今日记录\n\n日期：2026-10-22\n"))
        assertTrue(daily.contains("## 今日计划\n\n- [ ] "))
        val meeting = NoteTemplate.MEETING.render("设计讨论", date)
        assertTrue(meeting.contains("# 设计讨论"))
        assertTrue(meeting.contains("## 参会人员"))
        assertTrue(meeting.contains("## 待办"))
        assertFalse(meeting.contains("{{"))
    }

    @Test fun datePathsHandleRootNestedFolderAndLeapDay() {
        assertEquals("2026-10-22.md", dailyNotePath("", date))
        assertEquals("日志/每日/2024-02-29.md", dailyNotePath("日志/每日", LocalDate.of(2024, 2, 29)))
        assertEquals(NoteTemplate.DAILY, NoteTemplate.fromId("unknown"))
    }
}
