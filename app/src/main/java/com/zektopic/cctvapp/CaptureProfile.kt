package com.zektopic.cctvapp

/**
 * The knobs on the snapshot and detection pipeline.
 *
 * That pipeline -- capture a frame off the GL surface, JPEG-encode it, decode it back
 * down, run motion and object detection -- is described by the service's own comment as
 * "the single biggest battery cost in the app". Every parameter governing it was a
 * private constant, so the only way to trade detection latency against battery life was
 * to edit the source and rebuild.
 *
 * The defaults here are exactly the constants they replace, so nothing changes until
 * somebody deliberately changes it.
 *
 * Kept free of Android imports so the ranges and their interactions are unit tested.
 */
object CaptureProfile {

    /**
     * Milliseconds between captures while detection is on or someone is watching.
     *
     * 500 ms (2 fps) is the historical value. Raising it is the single most effective
     * battery saving available to a camera that only needs to notice that someone walked
     * past, not to catch the exact frame they did it in.
     */
    const val MIN_ACTIVE_INTERVAL_MS = 200
    const val MAX_ACTIVE_INTERVAL_MS = 5_000
    const val DEFAULT_ACTIVE_INTERVAL_MS = 500

    /**
     * Milliseconds between captures when nothing wants frames.
     *
     * This is only a heartbeat, kept short enough to notice a new dashboard viewer
     * promptly. 3000 ms is the historical value.
     */
    const val MIN_IDLE_INTERVAL_MS = 1_000
    const val MAX_IDLE_INTERVAL_MS = 30_000
    const val DEFAULT_IDLE_INTERVAL_MS = 3_000

    /**
     * Width frames are decoded down to before analysis.
     *
     * The object detector resizes to its own small input tensor anyway and the motion
     * detector scales down before differencing, so this mostly buys decode cost and peak
     * bitmap memory -- both roughly quadratic in the factor. 640 is the historical value.
     * Below about 320 the detector starts missing small or distant subjects.
     */
    const val MIN_ANALYSIS_WIDTH = 240
    const val MAX_ANALYSIS_WIDTH = 1_280
    const val DEFAULT_ANALYSIS_WIDTH = 640

    /**
     * JPEG quality for captured snapshots, 1..100.
     *
     * 50 is the historical value. It governs the dashboard preview, the image stored
     * with every detection event, and the bitmap the detectors see, so raising it costs
     * CPU and storage on every single frame while lowering it can start to cost
     * detection accuracy.
     */
    const val MIN_JPEG_QUALITY = 20
    const val MAX_JPEG_QUALITY = 95
    const val DEFAULT_JPEG_QUALITY = 50

    fun sanitizeActiveIntervalMs(ms: Int): Int =
        ms.coerceIn(MIN_ACTIVE_INTERVAL_MS, MAX_ACTIVE_INTERVAL_MS)

    /**
     * Clamps the idle interval, and never lets it fall below the active one.
     *
     * An idle heartbeat faster than the active cadence would mean a camera that captures
     * *more* often when nothing is watching, which inverts the whole point of the two
     * rates. The active interval wins because it is the one the user tuned for detection.
     */
    fun sanitizeIdleIntervalMs(ms: Int, activeIntervalMs: Int): Int =
        ms.coerceIn(MIN_IDLE_INTERVAL_MS, MAX_IDLE_INTERVAL_MS)
            .coerceAtLeast(sanitizeActiveIntervalMs(activeIntervalMs))

    fun sanitizeAnalysisWidth(width: Int): Int =
        width.coerceIn(MIN_ANALYSIS_WIDTH, MAX_ANALYSIS_WIDTH)

    fun sanitizeJpegQuality(quality: Int): Int =
        quality.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY)

    /**
     * The `inSampleSize` to decode a [sourceWidth]-wide JPEG down to about [targetWidth].
     *
     * BitmapFactory only honours powers of two, and this reproduces the loop it replaces:
     * halve while the result would still be at least the target, so the decode lands on
     * or above the target rather than below it.
     */
    fun sampleSizeFor(sourceWidth: Int, targetWidth: Int): Int {
        if (sourceWidth <= 0 || targetWidth <= 0) return 1
        var sampleSize = 1
        while (sourceWidth / (sampleSize * 2) >= targetWidth) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
