package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapUnlockTest {

    @Test
    fun `seven taps inside the window unlock`() {
        val unlock = TapUnlock()
        var now = 1_000L
        repeat(TapUnlock.REQUIRED_TAPS - 1) {
            assertFalse(unlock.tap(now).unlocked)
            now += 200
        }
        assertTrue(unlock.tap(now).unlocked)
    }

    @Test
    fun `the countdown stays silent for the first taps`() {
        val unlock = TapUnlock()
        var now = 0L
        val results = (1..TapUnlock.REQUIRED_TAPS).map {
            now += 100
            unlock.tap(now)
        }

        // Taps 1 and 2 say nothing; a user brushing the row should see no hint at all.
        assertFalse(results[0].showCountdown)
        assertFalse(results[1].showCountdown)
        assertTrue(results[TapUnlock.FEEDBACK_AFTER_TAPS - 1].showCountdown)
    }

    @Test
    fun `remaining counts down to the unlock`() {
        val unlock = TapUnlock()
        var now = 0L
        val remaining = (1..TapUnlock.REQUIRED_TAPS).map {
            now += 100
            unlock.tap(now).remaining
        }
        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), remaining)
    }

    @Test
    fun `a pause longer than the window restarts the run`() {
        val unlock = TapUnlock()
        var now = 0L
        repeat(6) {
            unlock.tap(now)
            now += 100
        }

        // Six taps in, then a long pause. The seventh tap must NOT unlock -- otherwise
        // taps accumulated over days would eventually open the section by accident.
        now += TapUnlock.TAP_WINDOW_MS + 1
        val afterPause = unlock.tap(now)
        assertFalse(afterPause.unlocked)
        assertEquals(TapUnlock.REQUIRED_TAPS - 1, afterPause.remaining)
    }

    @Test
    fun `a gap exactly on the window boundary still continues the run`() {
        val unlock = TapUnlock(requiredTaps = 2, windowMs = 1_000L)
        unlock.tap(0L)
        assertTrue(unlock.tap(1_000L).unlocked)
    }

    @Test
    fun `unlocking rearms the counter instead of counting past the threshold`() {
        val unlock = TapUnlock(requiredTaps = 2, windowMs = 1_000L)
        unlock.tap(0L)
        assertTrue(unlock.tap(100L).unlocked)

        // The very next tap begins a fresh run rather than reporting unlocked again.
        val next = unlock.tap(200L)
        assertFalse(next.unlocked)
        assertEquals(1, next.remaining)
    }

    @Test
    fun `reset abandons the current run`() {
        val unlock = TapUnlock()
        var now = 0L
        repeat(TapUnlock.REQUIRED_TAPS - 1) {
            unlock.tap(now)
            now += 100
        }
        unlock.reset()

        val afterReset = unlock.tap(now)
        assertFalse(afterReset.unlocked)
        assertEquals(TapUnlock.REQUIRED_TAPS - 1, afterReset.remaining)
    }
}
