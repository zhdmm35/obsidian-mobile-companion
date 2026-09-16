package com.obsidiancompanion.data.cache

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** §73：miss→remote→put / hit 立即 / SHA 区分版本 / clear / 跨实例持久。 */
class ContentCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newCache(): ContentCache = ContentCache(tmp.newFolder())

    @Test
    fun `miss then put then hit`() = runTest {
        val cache = newCache()
        assertNull(cache.get("abc123"))
        assertFalse(cache.exists("abc123"))

        cache.put("abc123", "# Hello".toByteArray())
        assertTrue(cache.exists("abc123"))
        assertArrayEquals("# Hello".toByteArray(), cache.get("abc123"))
        assertEquals(1, cache.count())
    }

    @Test
    fun `different sha never confused`() = runTest {
        val cache = newCache()
        cache.put("aaa111", "v1".toByteArray())
        cache.put("bbb222", "v2".toByteArray())
        assertArrayEquals("v1".toByteArray(), cache.get("aaa111"))
        assertArrayEquals("v2".toByteArray(), cache.get("bbb222"))
        assertNull(cache.get("ccc333"))
    }

    @Test
    fun `put same sha twice keeps single copy`() = runTest {
        val cache = newCache()
        cache.put("aaa111", "same".toByteArray())
        cache.put("aaa111", "same".toByteArray())
        assertEquals(1, cache.count())
    }

    @Test
    fun `delete removes single entry`() = runTest {
        val cache = newCache()
        cache.put("aaa111", "x".toByteArray())
        cache.put("bbb222", "y".toByteArray())
        cache.delete("aaa111")
        assertNull(cache.get("aaa111"))
        assertArrayEquals("y".toByteArray(), cache.get("bbb222"))
    }

    @Test
    fun `clear empties everything and reports size`() = runTest {
        val cache = newCache()
        cache.put("aaa111", "12345".toByteArray())
        cache.put("bbb222", "12345678".toByteArray())
        assertEquals(13L, cache.size())
        cache.clear()
        assertEquals(0L, cache.size())
        assertEquals(0, cache.count())
        assertNull(cache.get("aaa111"))
    }

    @Test
    fun `persists across instances on same directory`() = runTest {
        val dir = tmp.newFolder()
        ContentCache(dir).put("aaa111", "keep".toByteArray())
        assertArrayEquals("keep".toByteArray(), ContentCache(dir).get("aaa111"))
    }
}
