package com.obsidiancompanion.feature.editor

import com.obsidiancompanion.data.markdown.MarkdownParser
import com.obsidiancompanion.model.markdown.MdBlock
import com.obsidiancompanion.model.markdown.MdDocument
import com.obsidiancompanion.model.markdown.plainText
import org.junit.Assert.*
import org.junit.Test

class MarkdownTaskEditTest {
    @Test fun mixedListDoesNotTurnOrdinaryItemsIntoTasks() {
        val list = MarkdownParser.parse("- ordinary\n- [ ] task").blocks.single() as MdBlock.TaskList
        assertFalse(list.items[0].isTask)
        assertTrue(list.items[1].isTask)
        val ordered = MarkdownParser.parse("3. ordinary\n4. [ ] task").blocks.single() as MdBlock.TaskList
        assertEquals("3.", ordered.items[0].listMarker)
    }

    @Test fun editsOnlySelectedDuplicateAndPreservesFrontmatterAndCrlf() {
        val source = "---\r\ntitle: test\r\n---\r\n\r\n- [ ] same\r\n- [ ] same\r\n"
        val doc = MarkdownParser.parse(source)
        val task = tasks(doc)[1]
        assertEquals(source.replaceRange(source.lastIndexOf("[ ]") + 1, source.lastIndexOf("[ ]") + 2, "x"),
            toggleMarkdownTask(source, doc.frontmatter, task))
    }

    @Test fun editsNestedOrderedQuotedAndCalloutTasksAtSourcePositions() {
        val sources = listOf(
            "- parent\n  - [ ] target",
            "3. [ ] target\n4. ordinary",
            "> - [ ] target",
            "> [!TIP]\n> - [ ] target",
            "-\n  [ ] target",
            "\\[[escaped]]\n\n- [ ] target",
            "-\t[ ] target",
            "paragraph\r\r- [ ] target",
            "---\r\ntitle: test\r\n---\r\nparagraph\r- [ ] target",
        )
        sources.forEach { source ->
            val doc = MarkdownParser.parse(source)
            assertEquals(source, source.replace("[ ]", "[x]"), toggleMarkdownTask(source, doc.frontmatter, tasks(doc).single()))
        }
    }

    @Test fun uppercaseCompletionCanBeUncheckedWithoutOtherChanges() {
        val source = "- [X] done  \n  continuation\n"
        val doc = MarkdownParser.parse(source)
        assertEquals(source.replace("[X]", "[ ]"), toggleMarkdownTask(source, doc.frontmatter, tasks(doc).single()))
    }

    @Test fun codeAndEscapedTaskMarkersAreNeverEditableTasks() {
        val doc = MarkdownParser.parse("```md\n- [ ] code\n```\n\n- \\[ ] escaped\n\n- [ ] real")
        assertEquals(listOf("real"), tasks(doc).map { it.inlines.plainText() })
        val list = doc.blocks.last() as MdBlock.TaskList
        assertEquals("[ ] escaped", list.items.first().inlines.plainText())
    }

    @Test fun changedOrUnknownMarkersAreRejected() {
        val task = tasks(MarkdownParser.parse("- [ ] task")).single()
        assertNull(toggleMarkdownTask("- [x] task", null, task))
        assertNull(toggleMarkdownTask("text", null, task))
        assertNull(toggleMarkdownTask("- [ ] task", null, task.copy(sourceLine = null)))
        assertNull(toggleMarkdownTask("- [-] task", null, task))
    }

    private fun tasks(doc: MdDocument): List<MdBlock.TaskList.TaskItem> {
        fun walk(block: MdBlock): List<MdBlock.TaskList.TaskItem> = when (block) {
            is MdBlock.TaskList -> block.items.filter { it.isTask } + block.items.flatMap { it.children.flatMap(::walk) }
            is MdBlock.BulletList -> block.items.flatMap { it.children.flatMap(::walk) }
            is MdBlock.OrderedList -> block.items.flatMap { it.children.flatMap(::walk) }
            is MdBlock.Quote -> block.children.flatMap(::walk)
            is MdBlock.Callout -> block.blocks.flatMap(::walk)
            else -> emptyList()
        }
        return doc.blocks.flatMap(::walk)
    }
}
