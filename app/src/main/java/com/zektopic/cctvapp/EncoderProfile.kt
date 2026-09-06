package com.zektopic.cctvapp

/**
 * Turns a resolution, frame rate and codec into the numbers the video encoder is
 * prepared with.
 *
 * Kept free of Android imports so the arithmetic is unit tested on the JVM, in the same
 * spirit as [WebAuth], [MotionDetector] and [EventStore].
 *
 * ### What this replaces
 *
 * The service previously carried the whole policy inline:
 *
 * ```
 * val bitrate = when {
 *     videoWidth >= 1920 -> 6000 * 1024
 *     videoWidth >= 1280 -> 4000 * 1024
 *     else               -> 2000 * 1024
 * }
 * ```
 *
 * That ladder is wrong in three ways that all show up on real devices:
 *
 *  - **It keys on width alone.** A portrait 1080x1920 stream matches neither the 1920
 *    nor the 1280 arm and is encoded at 2 Mbit/s, a third of what the same pixels get
 *    in landscape.
 *  - **It has no ceiling above 1080p.** "Max" resolution on a 4K-capable phone
 *    resolves to 3840x2160 and is then starved at the 1080p bitrate -- a quarter of
 *    what those pixels need, which is exactly the mushy 4K stream users report.
 *  - **It ignores frame rate and codec.** H.265 and AV1 reach the same quality as
 *    H.264 at substantially lower bitrates, and 15 fps needs roughly half the bits of
 *    30. Handing all of them the same number wastes bandwidth on the efficient
 *    combinations and gives the expensive ones no more.
 */
object EncoderProfile {

    /** Frame rates offered in the UI. Anything in [MIN_FPS]..[MAX_FPS] is accepted. */
    val FPS_CHOICES = listOf(10, 15, 20, 24, 30)

    const val MIN_FPS = 5
    const val MAX_FPS = 60
    const val DEFAULT_FPS = 30

    /**
     * Seconds between keyframes.
     *
     * 2 is not an arbitrary default: it is the value RootEncoder's five-argument
     * `prepareVideo` overload passes internally, so it is exactly what this app has
     * been streaming with all along.
     *
     * Lower means a new RTSP client renders sooner and recovers from loss faster, at a
     * real bandwidth cost. Higher suits a fixed camera watched continuously.
     */
    const val MIN_KEYFRAME_INTERVAL_SECONDS = 1
    const val MAX_KEYFRAME_INTERVAL_SECONDS = 10
    const val DEFAULT_KEYFRAME_INTERVAL_SECONDS = 2

    /** Bounds for a hand-entered bitrate, in kbit/s. */
    const val MIN_BITRATE_KBPS = 200
    const val MAX_BITRATE_KBPS = 40_000

    /** Reference points the auto ladder is anchored to, at [DEFAULT_FPS] and H.264. */
    private const val PIXELS_1080P = 1920 * 1080
    private const val PIXELS_720P = 1280 * 720
    private const val BITRATE_1080P_KBPS = 6000
    private const val BITRATE_720P_KBPS = 4000
    private const val BITRATE_SD_KBPS = 2000

    /**
     * Roughly how much bitrate each codec needs for the same picture, relative to H.264.
     *
     * These are conservative readings of the usual published comparisons rather than
     * measurements from any one device -- deliberately less aggressive than the
     * "50% of H.264" H.265 is often credited with, because the encoder here is whatever
     * the handset ships and the cheap ones do not reach the headline numbers.
     */
    private fun codecEfficiencyPercent(codec: String): Int = when (codec.uppercase()) {
        "H265" -> 70
        "AV1" -> 60
        else -> 100
    }

    /**
     * The bitrate to use when the user has not pinned one, in kbit/s.
     *
     * Scales by pixel count (so orientation stops mattering), linearly by frame rate,
     * and by codec efficiency.
     */
    fun autoBitrateKbps(
        width: Int,
        height: Int,
        fps: Int = DEFAULT_FPS,
        codec: String = "H264"
    ): Int {
        val pixels = width.toLong() * height.toLong()
        if (pixels <= 0) return BITRATE_SD_KBPS

        val anchor = when {
            pixels >= PIXELS_1080P -> BITRATE_1080P_KBPS
            pixels >= PIXELS_720P -> BITRATE_720P_KBPS
            else -> BITRATE_SD_KBPS
        }

        // Beyond 1080p the ladder has no rung, so scale with area instead of flattening.
        val forPixels = if (pixels > PIXELS_1080P) {
            anchor.toLong() * pixels / PIXELS_1080P
        } else {
            anchor.toLong()
        }

        val forFps = forPixels * fps.coerceIn(MIN_FPS, MAX_FPS) / DEFAULT_FPS
        val forCodec = forFps * codecEfficiencyPercent(codec) / 100

        return forCodec.coerceIn(MIN_BITRATE_KBPS.toLong(), MAX_BITRATE_KBPS.toLong()).toInt()
    }

    /**
     * The bitrate actually handed to the encoder, in kbit/s.
     *
     * [manualKbps] of null (or a value outside the accepted range) means "auto".
     */
    fun resolveBitrateKbps(
        width: Int,
        height: Int,
        fps: Int,
        codec: String,
        manualKbps: Int?
    ): Int {
        if (manualKbps != null && manualKbps in MIN_BITRATE_KBPS..MAX_BITRATE_KBPS) {
            return manualKbps
        }
        return autoBitrateKbps(width, height, fps, codec)
    }

    /**
     * kbit/s to the bit/s the MediaCodec API expects.
     *
     * Note this multiplies by 1000, not 1024. The old inline ladder used `* 1024`, so
     * "6000" was really 6144 kbit/s. The 2.4% difference is immaterial to picture
     * quality but it does mean the number now shown in the UI is the number the encoder
     * is given, which matters once a user can type one in.
     */
    fun kbpsToBps(kbps: Int): Int = kbps * 1000

    /** Clamps a stored frame rate to something the encoder will accept. */
    fun sanitizeFps(fps: Int): Int = fps.coerceIn(MIN_FPS, MAX_FPS)

    /** Clamps a stored keyframe interval to something the encoder will accept. */
    fun sanitizeKeyframeIntervalSeconds(seconds: Int): Int =
        seconds.coerceIn(MIN_KEYFRAME_INTERVAL_SECONDS, MAX_KEYFRAME_INTERVAL_SECONDS)
}
