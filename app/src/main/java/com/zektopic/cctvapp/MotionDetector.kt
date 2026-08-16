package com.zektopic.cctvapp

import android.graphics.Bitmap
import kotlin.math.abs

class MotionDetector(
    private val sampleSize: Int = 96,
    thresholdRatio: Double = DEFAULT_THRESHOLD_RATIO,
    private val thresholdDelta: Int = 20
) {
    companion object {
        const val DEFAULT_THRESHOLD_RATIO = 0.08

        /** Sensitivity 1 (least twitchy) maps to this ratio. */
        private const val LEAST_SENSITIVE_RATIO = 0.30
        /** Sensitivity 10 (most twitchy) maps to this ratio. */
        private const val MOST_SENSITIVE_RATIO = 0.01

        /**
         * Maps a 1..10 user-facing sensitivity onto the fraction of the frame that has
         * to change before it counts as motion. Higher sensitivity => lower threshold.
         */
        fun sensitivityToThresholdRatio(sensitivity: Int): Double {
            val clamped = sensitivity.coerceIn(1, 10)
            val position = (clamped - 1) / 9.0
            return LEAST_SENSITIVE_RATIO + position * (MOST_SENSITIVE_RATIO - LEAST_SENSITIVE_RATIO)
        }

        /**
         * Fraction of samples that differ by at least [thresholdDelta] between two
         * equally sized luma buffers.
         *
         * Pulled out of the Bitmap path on purpose: this is the part worth unit testing,
         * and it needs no Android framework to run.
         */
        fun changedRatio(previous: IntArray, current: IntArray, thresholdDelta: Int): Double {
            require(previous.size == current.size) { "luma buffers must be the same size" }
            if (current.isEmpty()) return 0.0
            var changed = 0
            for (i in current.indices) {
                if (abs(current[i] - previous[i]) >= thresholdDelta) changed++
            }
            return changed.toDouble() / current.size.toDouble()
        }

        /** ITU-R BT.601 luma, integer arithmetic. */
        fun lumaOf(pixels: IntArray): IntArray {
            val luma = IntArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                luma[i] = (r * 30 + g * 59 + b * 11) / 100
            }
            return luma
        }
    }

    @Volatile
    private var thresholdRatio: Double = thresholdRatio

    private var previousLuma: IntArray? = null

    fun updateSensitivity(sensitivity: Int) {
        thresholdRatio = sensitivityToThresholdRatio(sensitivity)
    }

    fun detectMotion(bitmap: Bitmap): Double {
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)
        try {
            val pixels = IntArray(sampleSize * sampleSize)
            scaled.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)

            val luma = lumaOf(pixels)
            val prev = previousLuma
            previousLuma = luma
            if (prev == null) return 0.0

            return changedRatio(prev, luma, thresholdDelta)
        } finally {
            // createScaledBitmap may return the source itself when no scaling is needed;
            // recycling that would destroy the caller's bitmap mid-pipeline.
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun isMotionDetected(bitmap: Bitmap): Pair<Boolean, Double> {
        val ratio = detectMotion(bitmap)
        return Pair(ratio >= thresholdRatio, ratio)
    }
}
