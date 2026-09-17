package com.obsidiancompanion.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** 阅读器内嵌图的解码上限（最长边 px）：正文内按屏幕宽度展示足够清晰；点开放大由查看器独立解码。 */
const val READER_IMAGE_MAX_DIM = 1600

/**
 * BitmapFactory inSampleSize（2 的幂）：解码后最长边 ≤ maxDim 的最小采样。
 * 纯函数，可 JVM 单测。
 */
fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
    require(width > 0 && height > 0 && maxDim > 0) { "width/height/maxDim must be positive" }
    var sample = 1
    while (maxOf(width, height) / sample > maxDim) sample *= 2
    return sample
}

/**
 * 两遍解码（先读 bounds 再按采样率解码），返回的 Bitmap 最长边 ≤ maxDim。
 * 无法解码返回 null（调用方走占位卡）；需在 IO 线程调用。
 */
fun decodeDownsampled(bytes: ByteArray, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
