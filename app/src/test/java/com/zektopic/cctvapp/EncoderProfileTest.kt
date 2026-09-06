package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderProfileTest {

    @Test
    fun `the anchor resolutions keep their historical bitrates`() {
        // These three are what the old inline ladder produced at 30 fps on H.264.
        // Changing them would silently re-tune every existing install.
        assertEquals(2000, EncoderProfile.autoBitrateKbps(640, 480))
        assertEquals(4000, EncoderProfile.autoBitrateKbps(1280, 720))
        assertEquals(6000, EncoderProfile.autoBitrateKbps(1920, 1080))
    }

    @Test
    fun `portrait and landscape of the same size get the same bitrate`() {
        // The old ladder keyed on width alone, so 1080x1920 fell through to the
        // 640-wide arm and was encoded at a third of the landscape bitrate.
        assertEquals(
            EncoderProfile.autoBitrateKbps(1920, 1080),
            EncoderProfile.autoBitrateKbps(1080, 1920)
        )
        assertEquals(
            EncoderProfile.autoBitrateKbps(1280, 720),
            EncoderProfile.autoBitrateKbps(720, 1280)
        )
    }

    @Test
    fun `4K gets more than 1080p`() {
        // The old ladder flattened everything above 1920 wide to the 1080p bitrate,
        // which starved "Max" resolution on 4K-capable phones.
        val uhd = EncoderProfile.autoBitrateKbps(3840, 2160)
        val fhd = EncoderProfile.autoBitrateKbps(1920, 1080)
        assertEquals(fhd * 4, uhd)
        assertTrue(uhd > fhd)
    }

    @Test
    fun `halving the frame rate roughly halves the bitrate`() {
        val at30 = EncoderProfile.autoBitrateKbps(1280, 720, fps = 30)
        val at15 = EncoderProfile.autoBitrateKbps(1280, 720, fps = 15)
        assertEquals(at30 / 2, at15)
    }

    @Test
    fun `more efficient codecs are given less bitrate`() {
        val h264 = EncoderProfile.autoBitrateKbps(1920, 1080, codec = "H264")
        val h265 = EncoderProfile.autoBitrateKbps(1920, 1080, codec = "H265")
        val av1 = EncoderProfile.autoBitrateKbps(1920, 1080, codec = "AV1")

        assertTrue("H265 should need less than H264", h265 < h264)
        assertTrue("AV1 should need less than H265", av1 < h265)
    }

    @Test
    fun `codec names are matched case insensitively`() {
        assertEquals(
            EncoderProfile.autoBitrateKbps(1280, 720, codec = "H265"),
            EncoderProfile.autoBitrateKbps(1280, 720, codec = "h265")
        )
    }

    @Test
    fun `an unknown codec is treated as H264 rather than throwing`() {
        assertEquals(
            EncoderProfile.autoBitrateKbps(1280, 720, codec = "H264"),
            EncoderProfile.autoBitrateKbps(1280, 720, codec = "VP9")
        )
    }

    @Test
    fun `an unresolved resolution falls back instead of dividing by zero`() {
        // width and height are 0 until "Max" is resolved from the camera.
        assertEquals(2000, EncoderProfile.autoBitrateKbps(0, 0))
    }

    @Test
    fun `the auto bitrate never leaves the accepted range`() {
        val tiny = EncoderProfile.autoBitrateKbps(160, 120, fps = EncoderProfile.MIN_FPS)
        assertTrue(tiny >= EncoderProfile.MIN_BITRATE_KBPS)

        val huge = EncoderProfile.autoBitrateKbps(7680, 4320, fps = EncoderProfile.MAX_FPS)
        assertTrue(huge <= EncoderProfile.MAX_BITRATE_KBPS)
    }

    @Test
    fun `a manual bitrate wins over the auto ladder`() {
        val resolved = EncoderProfile.resolveBitrateKbps(
            width = 1920, height = 1080, fps = 30, codec = "H264", manualKbps = 1500
        )
        assertEquals(1500, resolved)
    }

    @Test
    fun `a null or out-of-range manual bitrate falls back to auto`() {
        val auto = EncoderProfile.autoBitrateKbps(1920, 1080)

        assertEquals(auto, EncoderProfile.resolveBitrateKbps(1920, 1080, 30, "H264", null))
        assertEquals(auto, EncoderProfile.resolveBitrateKbps(1920, 1080, 30, "H264", 0))
        assertEquals(auto, EncoderProfile.resolveBitrateKbps(1920, 1080, 30, "H264", -1))
        assertEquals(
            auto,
            EncoderProfile.resolveBitrateKbps(
                1920, 1080, 30, "H264", EncoderProfile.MAX_BITRATE_KBPS + 1
            )
        )
    }

    @Test
    fun `kbps converts to bps`() {
        assertEquals(2_000_000, EncoderProfile.kbpsToBps(2000))
    }

    @Test
    fun `stored values are clamped to what the encoder accepts`() {
        assertEquals(EncoderProfile.MIN_FPS, EncoderProfile.sanitizeFps(0))
        assertEquals(EncoderProfile.MAX_FPS, EncoderProfile.sanitizeFps(9999))
        assertEquals(30, EncoderProfile.sanitizeFps(30))

        assertEquals(
            EncoderProfile.MIN_KEYFRAME_INTERVAL_SECONDS,
            EncoderProfile.sanitizeKeyframeIntervalSeconds(0)
        )
        assertEquals(
            EncoderProfile.MAX_KEYFRAME_INTERVAL_SECONDS,
            EncoderProfile.sanitizeKeyframeIntervalSeconds(600)
        )
        assertEquals(2, EncoderProfile.sanitizeKeyframeIntervalSeconds(2))
    }

    @Test
    fun `every offered frame rate is inside the accepted range`() {
        EncoderProfile.FPS_CHOICES.forEach {
            assertEquals("FPS choice $it must survive sanitizing", it, EncoderProfile.sanitizeFps(it))
        }
    }
}
