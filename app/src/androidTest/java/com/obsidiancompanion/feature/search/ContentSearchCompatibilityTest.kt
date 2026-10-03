package com.obsidiancompanion.feature.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** Android 与 JVM 的正则空白定义可能不同，分别验证与原实现一致。 */
class ContentSearchCompatibilityTest {
    @Test fun whitespaceCollapsePreservesAndroidRegexSemantics() {
        val input = (0..0xffff).filter { it !in 0xd800..0xdfff }
            .joinToString("\t \n") { it.toChar().toString() }
        assertEquals(input.replace(Regex("\\s+"), " "), collapseContentWhitespace(input))
    }

}
