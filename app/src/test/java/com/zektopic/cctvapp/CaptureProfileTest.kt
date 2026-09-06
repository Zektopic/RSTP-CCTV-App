package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProfileTest {

    @Test
    fun `the defaults are the constants they replace`() {
        // These were private constants in CctvServerService. Changing any of them here
        // silently re-tunes the capture pipeline for every existing install.
        assertEquals(500, CaptureProfile.DEFAULT_ACTIVE_INTERVAL_MS)
        assertEquals(3_000, CaptureProfile.DEFAULT_IDLE_INTERVAL_MS)
        assertEquals(640, CaptureProfile.DEFAULT_ANALYSIS_WIDTH)
        assertEquals(50, CaptureProfile.DEFAULT_JPEG_QUALITY)
    }

    @Test
    fun `every default survives its own sanitizer`() {
        assertEquals(
            CaptureProfile.DEFAULT_ACTIVE_INTERVAL_MS,
            CaptureProfile.sanitizeActiveIntervalMs(CaptureProfile.DEFAULT_ACTIVE_INTERVAL_MS)
        )
        assertEquals(
            CaptureProfile.DEFAULT_IDLE_INTERVAL_MS,
            CaptureProfile.sanitizeIdleIntervalMs(
                CaptureProfile.DEFAULT_IDLE_INTERVAL_MS,
                CaptureProfile.DEFAULT_ACTIVE_INTERVAL_MS
            )
        )
        assertEquals(
            CaptureProfile.DEFAULT_ANALYSIS_WIDTH,
            CaptureProfile.sanitizeAnalysisWidth(CaptureProfile.DEFAULT_ANALYSIS_WIDTH)
        )
        assertEquals(
            CaptureProfile.DEFAULT_JPEG_QUALITY,
            CaptureProfile.sanitizeJpegQuality(CaptureProfile.DEFAULT_JPEG_QUALITY)
        )
    }

    @Test
    fun `values outside the range are clamped rather than accepted`() {
        assertEquals(
            CaptureProfile.MIN_ACTIVE_INTERVAL_MS,
            CaptureProfile.sanitizeActiveIntervalMs(0)
        )
        assertEquals(
            CaptureProfile.MAX_ACTIVE_INTERVAL_MS,
            CaptureProfile.sanitizeActiveIntervalMs(Int.MAX_VALUE)
        )
        assertEquals(CaptureProfile.MIN_ANALYSIS_WIDTH, CaptureProfile.sanitizeAnalysisWidth(1))
        assertEquals(CaptureProfile.MAX_JPEG_QUALITY, CaptureProfile.sanitizeJpegQuality(100))
        assertEquals(CaptureProfile.MIN_JPEG_QUALITY, CaptureProfile.sanitizeJpegQuality(-5))
    }

    @Test
    fun `the idle interval is never faster than the active one`() {
        // Otherwise the camera would capture MORE often when nothing is watching, which
        // inverts the entire point of having two rates.
        val active = 5_000
        val idle = CaptureProfile.sanitizeIdleIntervalMs(1_000, active)
        assertTrue("idle ($idle) must not be faster than active ($active)", idle >= active)
        assertEquals(active, idle)
    }

    @Test
    fun `a normal idle interval is left alone`() {
        assertEquals(3_000, CaptureProfile.sanitizeIdleIntervalMs(3_000, 500))
    }

    @Test
    fun `sample size reproduces the loop it replaces`() {
        // 1920 wide down to a 640 target: 1920/2 = 960 >= 640, so halve; 1920/4 = 480
        // which is below the target, so stop. Landing on or above the target, not below.
        assertEquals(2, CaptureProfile.sampleSizeFor(1920, 640))
        assertEquals(1, CaptureProfile.sampleSizeFor(640, 640))
        assertEquals(1, CaptureProfile.sampleSizeFor(320, 640))
        assertEquals(4, CaptureProfile.sampleSizeFor(3840, 640))
    }

    @Test
    fun `sample size is always a power of two`() {
        listOf(320, 640, 1280, 1920, 2560, 3840, 7680).forEach { source ->
            listOf(240, 320, 640, 1280).forEach { target ->
                val n = CaptureProfile.sampleSizeFor(source, target)
                assertTrue("$n from ${source}->$target is not a power of two", n > 0 && (n and (n - 1)) == 0)
            }
        }
    }

    @Test
    fun `a degenerate size does not hang or divide by zero`() {
        assertEquals(1, CaptureProfile.sampleSizeFor(0, 640))
        assertEquals(1, CaptureProfile.sampleSizeFor(1920, 0))
        assertEquals(1, CaptureProfile.sampleSizeFor(-1, -1))
    }

    @Test
    fun `the decoded width never falls below the target`() {
        listOf(640, 1280, 1920, 3840).forEach { source ->
            val target = 640
            val decoded = source / CaptureProfile.sampleSizeFor(source, target)
            if (source >= target) {
                assertTrue("decoded $decoded from $source fell below $target", decoded >= target)
            }
        }
    }
}
