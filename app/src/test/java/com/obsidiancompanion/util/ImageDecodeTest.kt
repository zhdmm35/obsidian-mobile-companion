package com.obsidiancompanion.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** inSampleSize：2 的幂 / 小图不采样 / 解码后最长边 ≤ maxDim。 */
class ImageDecodeTest {

    @Test
    fun `small image not sampled`() {
        assertEquals(1, calculateInSampleSize(1600, 900, 1600))
        assertEquals(1, calculateInSampleSize(800, 600, 1600))
    }

    @Test
    fun `landscape sampled to cap longest edge`() {
        assertEquals(2, calculateInSampleSize(3200, 2400, 1600)) // → 1600
        assertEquals(4, calculateInSampleSize(6000, 4000, 1600)) // → 1500
        assertEquals(8, calculateInSampleSize(12000, 8000, 1600)) // → 1500
    }

    @Test
    fun `portrait uses height as longest edge`() {
        assertEquals(4, calculateInSampleSize(3000, 6000, 1600))
    }

    @Test
    fun `sample stays power of two and decoded edge within cap`() {
        listOf(1, 100, 1599, 1600, 1601, 3000, 4096, 9000, 24000).forEach { dim ->
            val sample = calculateInSampleSize(dim, dim, 1600)
            assertTrue("sample must be power of two, was $sample", sample and (sample - 1) == 0)
            assertTrue("decoded edge ${dim / sample} exceeds cap", dim / sample <= 1600)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-positive input`() {
        calculateInSampleSize(0, 100, 1600)
    }
}
