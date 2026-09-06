package com.zektopic.cctvapp

/**
 * Counts the taps that reveal the hidden Advanced settings section.
 *
 * The advanced controls change encoder parameters that can stop the stream dead on
 * hardware that will not honour them, so they are deliberately not on the main screen.
 * Revealing them follows the convention Android itself uses for developer options: tap a
 * build-info row repeatedly, with a countdown once the user is clearly doing it on purpose.
 *
 * Kept free of Android imports so the counting rules can be unit tested on the JVM, in
 * the same spirit as [WebAuth] and [MotionDetector].
 */
class TapUnlock(
    private val requiredTaps: Int = REQUIRED_TAPS,
    private val windowMs: Long = TAP_WINDOW_MS
) {

    companion object {
        /** Taps needed to unlock. Matches the AOSP developer-options gesture. */
        const val REQUIRED_TAPS = 7

        /**
         * Maximum gap between two taps that still counts as the same run.
         *
         * Without a window the counter would accumulate across a whole session and a
         * user who idly tapped the version row on seven separate days would be handed
         * the advanced settings without ever intending to ask for them.
         */
        const val TAP_WINDOW_MS = 3_000L

        /** Stay silent until the user is plainly doing this deliberately. */
        const val FEEDBACK_AFTER_TAPS = 3
    }

    private var taps = 0
    private var lastTapMs = Long.MIN_VALUE

    /**
     * The outcome of one tap.
     *
     * @param remaining taps still needed; 0 once [unlocked].
     * @param unlocked true on the tap that completes the gesture, and only that tap.
     * @param showCountdown whether the UI should tell the user how many taps are left.
     */
    data class Result(
        val remaining: Int,
        val unlocked: Boolean,
        val showCountdown: Boolean
    )

    /** Forgets the current run, e.g. when the screen is left. */
    fun reset() {
        taps = 0
        lastTapMs = Long.MIN_VALUE
    }

    /** Registers a tap that happened at [nowMs] and reports what the UI should do. */
    fun tap(nowMs: Long): Result {
        val continuesRun = lastTapMs != Long.MIN_VALUE && nowMs - lastTapMs <= windowMs
        taps = if (continuesRun) taps + 1 else 1
        lastTapMs = nowMs

        val unlocked = taps >= requiredTaps
        if (unlocked) {
            // Leave the counter armed for a fresh run rather than letting it keep
            // climbing past the threshold.
            taps = 0
            lastTapMs = Long.MIN_VALUE
        }

        val remaining = if (unlocked) 0 else requiredTaps - taps
        return Result(
            remaining = remaining,
            unlocked = unlocked,
            showCountdown = !unlocked && taps >= FEEDBACK_AFTER_TAPS
        )
    }
}
