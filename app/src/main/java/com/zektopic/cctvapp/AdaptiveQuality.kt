package com.zektopic.cctvapp

/**
 * Decides how far to back off when the device is overheating or running out of battery.
 *
 * This is the honest answer to "make it work well across all Androids". The tempting
 * alternative -- a table of device models with hand-tuned settings for each -- cannot be
 * verified for hardware nobody on the project owns, covers none of the thousands of
 * models that are not in it, and rots as vendors ship firmware. Thermal status and
 * battery level are the device telling us how it is coping right now, which is true on
 * every handset including ones that do not exist yet.
 *
 * A phone streaming H.264 at 1080p30 with the screen off is a sustained load that most
 * handsets cannot hold indefinitely. Left alone the SoC throttles anyway -- the encoder
 * starts missing its deadlines, frames drop, and the stream stutters in a way that looks
 * like a network problem. Backing the bitrate off first keeps a lower-quality stream
 * running smoothly instead of a higher-quality one falling apart.
 *
 * Kept free of Android imports so the policy is unit tested on the JVM. The thermal
 * constants below mirror `android.os.PowerManager.THERMAL_STATUS_*`.
 */
object AdaptiveQuality {

    // Mirrors PowerManager.THERMAL_STATUS_*. There is a test asserting these still line
    // up with the platform values, so a drift cannot pass silently.
    const val THERMAL_NONE = 0
    const val THERMAL_LIGHT = 1
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3
    const val THERMAL_CRITICAL = 4
    const val THERMAL_EMERGENCY = 5
    const val THERMAL_SHUTDOWN = 6

    /** What pushed the quality down, so the UI can say why rather than looking broken. */
    enum class Trigger { NONE, THERMAL, BATTERY }

    /**
     * @param bitrateScalePercent percent of the configured bitrate to actually use.
     *   100 means untouched.
     * @param throttleCapture whether the snapshot and detection pipeline should drop to
     *   its idle cadence. That pipeline is the app's own comment's "single biggest
     *   battery cost", so it is the first thing worth giving up under real pressure.
     * @param trigger which condition produced this plan.
     */
    data class Plan(
        val bitrateScalePercent: Int,
        val throttleCapture: Boolean,
        val trigger: Trigger
    ) {
        /** True when the device is running exactly as configured. */
        val isUnrestricted: Boolean
            get() = bitrateScalePercent >= 100 && !throttleCapture
    }

    val UNRESTRICTED = Plan(100, throttleCapture = false, trigger = Trigger.NONE)

    /** Below this, and discharging, the stream is costing more than it is worth. */
    const val BATTERY_LOW_PERCENT = 30
    const val BATTERY_CRITICAL_PERCENT = 15

    /**
     * Works out how hard to back off.
     *
     * @param thermalStatus one of the `THERMAL_*` values. Devices below API 29 have no
     *   thermal API at all and pass [THERMAL_NONE]; battery adaptation still applies
     *   there, which is why the two conditions are evaluated independently.
     * @param batteryPercent 0..100, or -1 when it cannot be read.
     * @param charging whether the device is on power. A plugged-in camera is the normal
     *   deployment for this app and must not be throttled for a low battery reading
     *   taken while it charges.
     */
    fun plan(thermalStatus: Int, batteryPercent: Int, charging: Boolean): Plan {
        val thermal = thermalPlan(thermalStatus)
        val battery = batteryPlan(batteryPercent, charging)

        // Take the stricter of the two. On a tie thermal wins the attribution: heat is
        // the condition that damages the device and the one the user can act on.
        return when {
            thermal.bitrateScalePercent <= battery.bitrateScalePercent -> {
                thermal.copy(throttleCapture = thermal.throttleCapture || battery.throttleCapture)
            }
            else -> {
                battery.copy(throttleCapture = thermal.throttleCapture || battery.throttleCapture)
            }
        }
    }

    private fun thermalPlan(status: Int): Plan = when {
        // LIGHT is normal for a phone doing sustained work and does not warrant giving
        // up picture quality; the platform documents it as not user-visible.
        status <= THERMAL_LIGHT -> UNRESTRICTED
        status == THERMAL_MODERATE -> Plan(70, throttleCapture = false, trigger = Trigger.THERMAL)
        status == THERMAL_SEVERE -> Plan(50, throttleCapture = true, trigger = Trigger.THERMAL)
        // CRITICAL and beyond: the platform is already shedding load and a shutdown is
        // on the table. Stream something rather than pretending nothing is wrong.
        else -> Plan(35, throttleCapture = true, trigger = Trigger.THERMAL)
    }

    private fun batteryPlan(batteryPercent: Int, charging: Boolean): Plan {
        if (charging || batteryPercent < 0) return UNRESTRICTED
        return when {
            batteryPercent <= BATTERY_CRITICAL_PERCENT ->
                Plan(50, throttleCapture = true, trigger = Trigger.BATTERY)
            batteryPercent <= BATTERY_LOW_PERCENT ->
                Plan(70, throttleCapture = false, trigger = Trigger.BATTERY)
            else -> UNRESTRICTED
        }
    }

    /** Applies a plan's scale to a bitrate, never dropping below what an encoder accepts. */
    fun scaleBitrateKbps(configuredKbps: Int, plan: Plan): Int =
        (configuredKbps.toLong() * plan.bitrateScalePercent / 100)
            .coerceAtLeast(EncoderProfile.MIN_BITRATE_KBPS.toLong())
            .toInt()
}
