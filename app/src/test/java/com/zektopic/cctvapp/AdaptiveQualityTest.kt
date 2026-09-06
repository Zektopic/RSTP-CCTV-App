package com.zektopic.cctvapp

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveQualityTest {

    private fun plan(thermal: Int = AdaptiveQuality.THERMAL_NONE, battery: Int = 100, charging: Boolean = false) =
        AdaptiveQuality.plan(thermal, battery, charging)

    @Test
    fun `the mirrored thermal constants match the platform`() {
        // AdaptiveQuality copies these so it can stay free of Android imports and be
        // tested here. They are compile-time constants, so this comparison is resolved
        // by the compiler against the real android.jar rather than at runtime -- if the
        // platform ever renumbers them, this stops compiling or fails, instead of the
        // app silently mapping SEVERE onto MODERATE's policy.
        assertEquals(PowerManager.THERMAL_STATUS_NONE, AdaptiveQuality.THERMAL_NONE)
        assertEquals(PowerManager.THERMAL_STATUS_LIGHT, AdaptiveQuality.THERMAL_LIGHT)
        assertEquals(PowerManager.THERMAL_STATUS_MODERATE, AdaptiveQuality.THERMAL_MODERATE)
        assertEquals(PowerManager.THERMAL_STATUS_SEVERE, AdaptiveQuality.THERMAL_SEVERE)
        assertEquals(PowerManager.THERMAL_STATUS_CRITICAL, AdaptiveQuality.THERMAL_CRITICAL)
        assertEquals(PowerManager.THERMAL_STATUS_EMERGENCY, AdaptiveQuality.THERMAL_EMERGENCY)
        assertEquals(PowerManager.THERMAL_STATUS_SHUTDOWN, AdaptiveQuality.THERMAL_SHUTDOWN)
    }

    @Test
    fun `a cool device on a full battery is left alone`() {
        assertTrue(plan().isUnrestricted)
        assertEquals(AdaptiveQuality.Trigger.NONE, plan().trigger)
    }

    @Test
    fun `light throttling is not worth giving up picture quality for`() {
        // The platform documents LIGHT as not user-visible, and it is normal for a
        // phone doing sustained work. Reacting to it would mean permanently degrading
        // the stream on any device that runs warm.
        assertTrue(plan(thermal = AdaptiveQuality.THERMAL_LIGHT).isUnrestricted)
    }

    @Test
    fun `each thermal step backs off further`() {
        val moderate = plan(thermal = AdaptiveQuality.THERMAL_MODERATE)
        val severe = plan(thermal = AdaptiveQuality.THERMAL_SEVERE)
        val critical = plan(thermal = AdaptiveQuality.THERMAL_CRITICAL)

        assertTrue(moderate.bitrateScalePercent < 100)
        assertTrue(severe.bitrateScalePercent < moderate.bitrateScalePercent)
        assertTrue(critical.bitrateScalePercent < severe.bitrateScalePercent)

        listOf(moderate, severe, critical).forEach {
            assertEquals(AdaptiveQuality.Trigger.THERMAL, it.trigger)
        }
    }

    @Test
    fun `the states past critical are treated at least as seriously as critical`() {
        val critical = plan(thermal = AdaptiveQuality.THERMAL_CRITICAL)
        listOf(AdaptiveQuality.THERMAL_EMERGENCY, AdaptiveQuality.THERMAL_SHUTDOWN).forEach {
            val p = plan(thermal = it)
            assertTrue(
                "status $it must not be gentler than CRITICAL",
                p.bitrateScalePercent <= critical.bitrateScalePercent
            )
            assertTrue(p.throttleCapture)
        }
    }

    @Test
    fun `capture is only throttled once things are actually serious`() {
        assertFalse(plan(thermal = AdaptiveQuality.THERMAL_MODERATE).throttleCapture)
        assertTrue(plan(thermal = AdaptiveQuality.THERMAL_SEVERE).throttleCapture)
    }

    @Test
    fun `a low battery backs off while discharging`() {
        val low = plan(battery = AdaptiveQuality.BATTERY_LOW_PERCENT)
        val critical = plan(battery = AdaptiveQuality.BATTERY_CRITICAL_PERCENT)

        assertTrue(low.bitrateScalePercent < 100)
        assertTrue(critical.bitrateScalePercent < low.bitrateScalePercent)
        assertEquals(AdaptiveQuality.Trigger.BATTERY, low.trigger)
        assertTrue(critical.throttleCapture)
    }

    @Test
    fun `a charging device is never throttled for its battery level`() {
        // A permanently plugged-in phone is the normal deployment for this app. Reading
        // 8% while it charges must not degrade the stream.
        val p = plan(battery = 8, charging = true)
        assertTrue(p.isUnrestricted)
    }

    @Test
    fun `an unreadable battery level is not treated as empty`() {
        // getBatteryLevel() returns -1 when it cannot be determined. Treating that as
        // 0% would throttle every device that fails to report.
        assertTrue(plan(battery = -1).isUnrestricted)
    }

    @Test
    fun `heat still throttles a charging device`() {
        // Charging is exactly when a phone gets hottest, so the thermal path must not
        // inherit the battery path's charging exemption.
        val p = plan(thermal = AdaptiveQuality.THERMAL_SEVERE, battery = 100, charging = true)
        assertEquals(AdaptiveQuality.Trigger.THERMAL, p.trigger)
        assertTrue(p.bitrateScalePercent < 100)
    }

    @Test
    fun `the stricter of the two conditions wins`() {
        val p = plan(
            thermal = AdaptiveQuality.THERMAL_MODERATE,   // 70%
            battery = AdaptiveQuality.BATTERY_CRITICAL_PERCENT // 50%
        )
        assertEquals(50, p.bitrateScalePercent)
        assertEquals(AdaptiveQuality.Trigger.BATTERY, p.trigger)
    }

    @Test
    fun `capture throttling from either condition survives the merge`() {
        // Severe heat throttles capture; a merely-low battery does not. Picking the
        // battery plan for its bitrate must not discard the thermal capture decision.
        val p = plan(
            thermal = AdaptiveQuality.THERMAL_SEVERE,          // 50%, throttles
            battery = AdaptiveQuality.BATTERY_CRITICAL_PERCENT // 50%, throttles
        )
        assertTrue(p.throttleCapture)

        val mixed = plan(thermal = AdaptiveQuality.THERMAL_SEVERE, battery = 100)
        assertTrue("thermal capture throttling must not be lost", mixed.throttleCapture)
    }

    @Test
    fun `thermal wins the attribution on a tie`() {
        val p = plan(
            thermal = AdaptiveQuality.THERMAL_SEVERE,          // 50%
            battery = AdaptiveQuality.BATTERY_CRITICAL_PERCENT // also 50%
        )
        assertEquals(AdaptiveQuality.Trigger.THERMAL, p.trigger)
    }

    @Test
    fun `scaling reduces the bitrate proportionally`() {
        val p = AdaptiveQuality.Plan(50, throttleCapture = false, trigger = AdaptiveQuality.Trigger.THERMAL)
        assertEquals(3000, AdaptiveQuality.scaleBitrateKbps(6000, p))
    }

    @Test
    fun `scaling never falls below what an encoder will accept`() {
        val p = AdaptiveQuality.Plan(35, throttleCapture = true, trigger = AdaptiveQuality.Trigger.THERMAL)
        val scaled = AdaptiveQuality.scaleBitrateKbps(EncoderProfile.MIN_BITRATE_KBPS, p)
        assertEquals(EncoderProfile.MIN_BITRATE_KBPS, scaled)
    }

    @Test
    fun `an unrestricted plan leaves the bitrate exactly as configured`() {
        assertEquals(6000, AdaptiveQuality.scaleBitrateKbps(6000, AdaptiveQuality.UNRESTRICTED))
    }
}
