package com.obsidiancompanion.feature.files

import java.time.LocalDate

/** 只给新建文件提供初始正文；不对已有笔记套用模板。 */
enum class NoteTemplate(val id: String, val label: String) {
    EMPTY("empty", "空白"),
    DAILY("daily", "日记"),
    MEETING("meeting", "会议记录");

    fun render(title: String, date: LocalDate): String = when (this) {
        EMPTY -> ""
        DAILY -> "# $title\n\n日期：$date\n\n## 今日计划\n\n- [ ] \n\n## 随手记录\n\n\n## 今日回顾\n\n"
        MEETING -> "# $title\n\n日期：$date\n\n## 参会人员\n\n- \n\n## 议题与记录\n\n\n## 待办\n\n- [ ] \n"
    }

    companion object {
        fun fromId(id: String): NoteTemplate = entries.firstOrNull { it.id == id } ?: DAILY
    }
}

fun dailyNotePath(folder: String, date: LocalDate): String =
    if (folder.isEmpty()) "$date.md" else "$folder/$date.md"
