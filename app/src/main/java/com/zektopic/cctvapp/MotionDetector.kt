package com.zektopic.cctvapp

import android.graphics.Bitmap
import kotlin.math.abs

class MotionDetector(
    private val sampleSize: Int = 96,
    private val thresholdRatio: Double = 0.08,
    private val thresholdDelta: Int = 20
) {
    private var previousLuma: IntArray? = null

    fun detectMotion(bitmap: Bitmap): Double {
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)
        val pixels = IntArray(sampleSize * sampleSize)
        scaled.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)

        val luma = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = (r * 30 + g * 59 + b * 11) / 100
        }

        val prev = previousLuma
        previousLuma = luma
        if (prev == null) return 0.0

        var changed = 0
        for (i in luma.indices) {
            if (abs(luma[i] - prev[i]) >= thresholdDelta) {
                changed++
            }
        }
        return changed.toDouble() / luma.size.toDouble()
    }

    fun isMotionDetected(bitmap: Bitmap): Pair<Boolean, Double> {
        val ratio = detectMotion(bitmap)
        return Pair(ratio >= thresholdRatio, ratio)
    }
}
