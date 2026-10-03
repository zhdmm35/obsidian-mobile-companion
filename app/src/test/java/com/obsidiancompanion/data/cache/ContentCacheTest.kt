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

    @Test fun `note statistics exclude images and obsolete versions but count shared notes`() = runTest {
        val cache = newCache()
        cache.put("note111", "body".toByteArray())
        cache.put("image22", byteArrayOf(1, 2))
        cache.put("old3333", "old".toByteArray())
        val stat = cache.stat(listOf("note111", "note111", "missing"))
        assertEquals(2, stat.count)
        assertEquals(9L, stat.bytes)
    }

    @Test fun `budget cleanup preserves current blobs draft bases and recent writes`() = runTest {
        val folder = tmp.newFolder()
        val cache = ContentCache(folder)
        for (sha in listOf("current", "draft11", "old1111", "old2222", "recent1")) {
            cache.put(sha, ByteArray(10))
            if (sha != "recent1") java.io.File(folder, "${sha.take(2)}/${sha.drop(2)}").setLastModified(1L)
        }
        val remaining = cache.trimObsolete(setOf("current", "draft11"), maxBytes = 25, nowMs = System.currentTimeMillis())
        assertEquals(30L, remaining)
        assertTrue(cache.exists("current"))
        assertTrue(cache.exists("draft11"))
        assertTrue(cache.exists("recent1"))
        assertFalse(cache.exists("old1111"))
        assertFalse(cache.exists("old2222"))
    }

    @Test fun `below budget does not delete unreferenced cache`() = runTest {
        val cache = newCache()
        cache.put("old1111", ByteArray(10))
        assertEquals(10L, cache.trimObsolete(emptySet(), maxBytes = 100))
        assertTrue(cache.exists("old1111"))
    }

    @Test
    fun `miss then put then hit`() = runTest {
        val cache = newCache()
        assertNull(cache.get("abc123"))
        assertFalse(cache.exists("abc123"))

        cache.put("abc123", "# Hello".toByteArray())
        assertTrue(cache.exists("abc123"))
        assertEquals(7L, cache.length("abc123"))
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
    fun `clearExcept preserves draft base blob`() = runTest {
        val cache = newCache()
        cache.put("aaa111", "draft base".toByteArray())
        cache.put("bbb222", "disposable".toByteArray())
        cache.clearExcept(setOf("aaa111"))
        assertTrue(cache.exists("aaa111"))
        assertFalse(cache.exists("bbb222"))
    }

    @Test
    fun `persists across instances on same directory`() = runTest {
        val dir = tmp.newFolder()
        ContentCache(dir).put("aaa111", "keep".toByteArray())
        assertArrayEquals("keep".toByteArray(), ContentCache(dir).get("aaa111"))
    }
}
