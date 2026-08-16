package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure parts of motion detection. The Bitmap-facing wrapper needs the
 * Android framework, but the arithmetic that decides whether something moved does not.
 */
class MotionDetectorTest {

    @Test
    fun `identical frames report no change`() {
        val frame = IntArray(100) { 128 }
        assertEquals(0.0, MotionDetector.changedRatio(frame, frame, 20), 1e-9)
    }

    @Test
    fun `a fully changed frame reports one`() {
        val previous = IntArray(100) { 0 }
        val current = IntArray(100) { 255 }
        assertEquals(1.0, MotionDetector.changedRatio(previous, current, 20), 1e-9)
    }

    @Test
    fun `only differences at or above the delta count`() {
        val previous = IntArray(10) { 100 }
        // Half the samples move by exactly the threshold, half by one less.
        val current = IntArray(10) { index -> if (index < 5) 120 else 119 }
        assertEquals(0.5, MotionDetector.changedRatio(previous, current, 20), 1e-9)
    }

    @Test
    fun `change is symmetric in direction`() {
        val a = IntArray(10) { 200 }
        val b = IntArray(10) { 100 }
        assertEquals(
            MotionDetector.changedRatio(a, b, 20),
            MotionDetector.changedRatio(b, a, 20),
            1e-9
        )
    }

    @Test
    fun `an empty frame reports no change`() {
        assertEquals(0.0, MotionDetector.changedRatio(IntArray(0), IntArray(0), 20), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mismatched frame sizes are rejected`() {
        MotionDetector.changedRatio(IntArray(4), IntArray(9), 20)
    }

    @Test
    fun `luma of pure colours follows bt601 weights`() {
        val luma = MotionDetector.lumaOf(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        assertEquals(76, luma[0])   // red   -> 255 * 30 / 100
        assertEquals(150, luma[1])  // green -> 255 * 59 / 100
        assertEquals(28, luma[2])   // blue  -> 255 * 11 / 100
    }

    @Test
    fun `luma ignores the alpha channel`() {
        val opaque = MotionDetector.lumaOf(intArrayOf(0xFF808080.toInt()))
        val transparent = MotionDetector.lumaOf(intArrayOf(0x00808080))
        assertEquals(opaque[0], transparent[0])
    }

    @Test
    fun `black and white sit at the ends of the range`() {
        val luma = MotionDetector.lumaOf(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        assertEquals(0, luma[0])
        assertEquals(255, luma[1])
    }

    @Test
    fun `higher sensitivity lowers the trigger threshold`() {
        val least = MotionDetector.sensitivityToThresholdRatio(1)
        val middle = MotionDetector.sensitivityToThresholdRatio(5)
        val most = MotionDetector.sensitivityToThresholdRatio(10)

        assertTrue("sensitivity should be monotonic", least > middle && middle > most)
        assertTrue(most > 0.0)
        assertTrue(least < 1.0)
    }

    @Test
    fun `sensitivity input is clamped to the supported range`() {
        assertEquals(
            MotionDetector.sensitivityToThresholdRatio(1),
            MotionDetector.sensitivityToThresholdRatio(-50),
            1e-9
        )
        assertEquals(
            MotionDetector.sensitivityToThresholdRatio(10),
            MotionDetector.sensitivityToThresholdRatio(9999),
            1e-9
        )
    }
}
