package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression cover for the resolution picker.
 *
 * The picker is an AutoCompleteTextView whose contents are ultimately arbitrary text;
 * the old `parts[0].toInt()` threw NumberFormatException and crashed the app.
 */
class ResolutionParsingTest {

    @Test
    fun `parses the standard presets`() {
        assertEquals(Pair(640, 480), MainActivity.parseResolution("640x480"))
        assertEquals(Pair(1280, 720), MainActivity.parseResolution("1280x720"))
        assertEquals(Pair(1920, 1080), MainActivity.parseResolution("1920x1080"))
    }

    @Test
    fun `max maps to the sentinel the service resolves`() {
        assertEquals(MainActivity.MAX_RESOLUTION, MainActivity.parseResolution("Max"))
        assertEquals(MainActivity.MAX_RESOLUTION, MainActivity.parseResolution("max"))
        assertEquals(MainActivity.MAX_RESOLUTION, MainActivity.parseResolution("  MAX  "))
    }

    @Test
    fun `tolerates surrounding whitespace and a capital separator`() {
        assertEquals(Pair(1280, 720), MainActivity.parseResolution(" 1280 x 720 "))
        assertEquals(Pair(1280, 720), MainActivity.parseResolution("1280X720"))
    }

    @Test
    fun `garbage input falls back instead of crashing`() {
        val fallback = Pair(640, 480)
        assertEquals(fallback, MainActivity.parseResolution(""))
        assertEquals(fallback, MainActivity.parseResolution("   "))
        assertEquals(fallback, MainActivity.parseResolution("hello"))
        assertEquals(fallback, MainActivity.parseResolution("1920"))
        assertEquals(fallback, MainActivity.parseResolution("1920x"))
        assertEquals(fallback, MainActivity.parseResolution("x1080"))
        assertEquals(fallback, MainActivity.parseResolution("axb"))
        assertEquals(fallback, MainActivity.parseResolution("1920x1080x60"))
        assertEquals(fallback, MainActivity.parseResolution("99999999999x1080"))
    }

    @Test
    fun `non positive dimensions fall back`() {
        val fallback = Pair(640, 480)
        assertEquals(fallback, MainActivity.parseResolution("0x480"))
        assertEquals(fallback, MainActivity.parseResolution("-640x480"))
        assertEquals(fallback, MainActivity.parseResolution("640x-480"))
    }
}
