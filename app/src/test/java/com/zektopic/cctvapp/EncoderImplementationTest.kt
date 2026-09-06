package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderImplementationTest {

    @Test
    fun `every value maps to a real CodecUtil CodecType constant`() {
        // RootEncoder 2.7.2 declares exactly these four. A typo here would only surface
        // as a runtime failure to start the stream, on whichever device picked it.
        val known = setOf("FIRST_COMPATIBLE_FOUND", "SOFTWARE", "HARDWARE", "CBR_PRIORITY")
        val mapped = EncoderImplementation.entries.map { it.codecTypeName }

        assertTrue("Unknown CodecType names: ${mapped - known}", known.containsAll(mapped))
        assertEquals("Each CodecType should be reachable", 4, mapped.toSet().size)
    }

    @Test
    fun `stored values round-trip`() {
        EncoderImplementation.entries.forEach {
            assertEquals(it, EncoderImplementation.fromStored(it.storedValue))
        }
    }

    @Test
    fun `an unknown or missing stored value falls back to the default`() {
        assertEquals(EncoderImplementation.DEFAULT, EncoderImplementation.fromStored(null))
        assertEquals(EncoderImplementation.DEFAULT, EncoderImplementation.fromStored(""))
        assertEquals(EncoderImplementation.DEFAULT, EncoderImplementation.fromStored("quantum"))
    }

    @Test
    fun `the default is the behaviour the old switch had when it was off`() {
        assertEquals(EncoderImplementation.AUTO, EncoderImplementation.DEFAULT)
        assertEquals("FIRST_COMPATIBLE_FOUND", EncoderImplementation.DEFAULT.codecTypeName)
    }

    @Test
    fun `the retired force_software boolean migrates without changing behaviour`() {
        // false must become AUTO, not HARDWARE: every existing install has false, and
        // mapping it to HARDWARE would change what they stream on the next launch.
        assertEquals(
            EncoderImplementation.AUTO,
            EncoderImplementation.fromLegacyForceSoftware(false)
        )
        assertEquals(
            EncoderImplementation.SOFTWARE,
            EncoderImplementation.fromLegacyForceSoftware(true)
        )
    }
}

class CodecSupportTest {

    private val hardwareOnly = CodecSupport(hardware = true, software = false)
    private val softwareOnly = CodecSupport(hardware = false, software = true)
    private val both = CodecSupport(hardware = true, software = true)

    @Test
    fun `available means some encoder exists`() {
        assertTrue(hardwareOnly.available)
        assertTrue(softwareOnly.available)
        assertTrue(both.available)
        assertFalse(CodecSupport.NONE.available)
    }

    @Test
    fun `a specific request needs that specific kind of encoder`() {
        assertTrue(hardwareOnly.supports(EncoderImplementation.HARDWARE))
        assertFalse(hardwareOnly.supports(EncoderImplementation.SOFTWARE))

        assertTrue(softwareOnly.supports(EncoderImplementation.SOFTWARE))
        assertFalse(softwareOnly.supports(EncoderImplementation.HARDWARE))
    }

    @Test
    fun `the searching modes accept either kind of encoder`() {
        listOf(EncoderImplementation.AUTO, EncoderImplementation.CBR_PRIORITY).forEach {
            assertTrue("$it should accept a hardware encoder", hardwareOnly.supports(it))
            assertTrue("$it should accept a software encoder", softwareOnly.supports(it))
        }
    }

    @Test
    fun `nothing is supported when the device has no encoder for the codec`() {
        EncoderImplementation.entries.forEach {
            assertFalse("$it should be unusable with no encoder", CodecSupport.NONE.supports(it))
        }
    }
}
