package com.obsidiancompanion.feature.search

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import com.obsidiancompanion.data.cache.ContentCache
import com.obsidiancompanion.data.metadata.entities.EntryKind
import com.obsidiancompanion.data.metadata.entities.RepoEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.system.measureNanoTime

/** 合成数据，不依赖网络；计时只用于比较，不设置易受设备影响的耗时断言。 */
@LargeTest
class ContentSearchBenchmarkTest {
    @Test fun scan500CachedNotes_preservesResultsAndReportsTiming() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val folder = File(context.cacheDir, "search-benchmark")
        folder.deleteRecursively()
        try {
            val cache = ContentCache(folder)
            val entries = (0 until 500).map { i ->
                val content = "alpha beta gamma delta common markdown text\n".repeat(1600).take(65_500) +
                    if (i % 50 == 0) "\nRareKeyword 跨行\n关键词" else "\nordinary ending"
                val sha = "note" + i.toString().padStart(6, '0')
                cache.put(sha, content.toByteArray())
                RepoEntryEntity("o/r", "$i.md", "$i.md", null, EntryKind.MARKDOWN, sha, content.toByteArray().size.toLong(), null)
            }
            for (query in listOf("rarekeyword", "跨行 关键词")) {
                val samples = mutableListOf<Long>()
                val loadSamples = mutableListOf<Long>()
                repeat(3) {
                    var loadNs = 0L
                    val elapsed = measureNanoTime {
                        val results = withContext(Dispatchers.Default) {
                            searchCachedContents(entries, query) {
                                val start = System.nanoTime()
                                val content = cache.get(it.blobSha)?.toString(Charsets.UTF_8)
                                loadNs += System.nanoTime() - start
                                content
                            }
                        }
                        assertEquals(10, results.size)
                        assertEquals(10, results.sumOf { it.count })
                    } / 1_000_000
                    samples += elapsed
                    loadSamples += loadNs / 1_000_000
                }
                Log.i("SearchBenchmark", "dispatcher=Default query=$query loadMs=$loadSamples ms=$samples median=${samples.sorted()[1]} bytes=${cache.size()}")
            }
        } finally { folder.deleteRecursively() }
    }
}
