package com.zektopic.cctvapp

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized SharedPreferences helper for persisting user settings
 * across app restarts, service restarts, and device reboots.
 */
object AppPreferences {

    private const val PREFS_NAME = "cctv_app_prefs"

    private const val KEY_VIDEO_CODEC = "video_codec"
    private const val KEY_VIDEO_WIDTH = "video_width"
    private const val KEY_VIDEO_HEIGHT = "video_height"
    private const val KEY_FORCE_SOFTWARE = "force_software"
    private const val KEY_SHOW_PREVIEW = "show_preview"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Video Codec ---
    fun getVideoCodec(context: Context): String =
        prefs(context).getString(KEY_VIDEO_CODEC, "H264") ?: "H264"

    fun setVideoCodec(context: Context, codec: String) {
        prefs(context).edit().putString(KEY_VIDEO_CODEC, codec).apply()
    }

    // --- Resolution ---
    fun getVideoWidth(context: Context): Int =
        prefs(context).getInt(KEY_VIDEO_WIDTH, 640)

    fun getVideoHeight(context: Context): Int =
        prefs(context).getInt(KEY_VIDEO_HEIGHT, 480)

    fun setResolution(context: Context, width: Int, height: Int) {
        prefs(context).edit()
            .putInt(KEY_VIDEO_WIDTH, width)
            .putInt(KEY_VIDEO_HEIGHT, height)
            .apply()
    }

    // --- Force Software Codec ---
    fun getForceSoftware(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_SOFTWARE, false)

    fun setForceSoftware(context: Context, force: Boolean) {
        prefs(context).edit().putBoolean(KEY_FORCE_SOFTWARE, force).apply()
    }

    // --- Show Preview ---
    fun getShowPreview(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_PREVIEW, false)

    fun setShowPreview(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_PREVIEW, show).apply()
    }

    // --- RTSP Authentication ---
    private const val KEY_AUTH_ENABLED = "rtsp_auth_enabled"
    private const val KEY_AUTH_USERNAME = "rtsp_username"
    private const val KEY_AUTH_PASSWORD = "rtsp_password"

    fun getAuthEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTH_ENABLED, false)

    fun setAuthEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTH_ENABLED, enabled).apply()
    }

    fun getUsername(context: Context): String =
        prefs(context).getString(KEY_AUTH_USERNAME, "") ?: ""

    fun setUsername(context: Context, username: String) {
        prefs(context).edit().putString(KEY_AUTH_USERNAME, username).apply()
    }

    fun getPassword(context: Context): String =
        prefs(context).getString(KEY_AUTH_PASSWORD, "") ?: ""

    fun setPassword(context: Context, password: String) {
        prefs(context).edit().putString(KEY_AUTH_PASSWORD, password).apply()
    }

    // --- Timestamp Overlay ---
    private const val KEY_SHOW_TIMESTAMP = "show_timestamp"
    private const val KEY_SHOW_DATE = "show_date"
    private const val KEY_TIMESTAMP_POSITION = "timestamp_position"

    fun getShowTimestamp(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_TIMESTAMP, false)

    fun setShowTimestamp(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_TIMESTAMP, show).apply()
    }

    fun getShowDate(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_DATE, false)

    fun setShowDate(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_DATE, show).apply()
    }

    fun getTimestampPosition(context: Context): String =
        prefs(context).getString(KEY_TIMESTAMP_POSITION, "Top Left") ?: "Top Left"

    fun setTimestampPosition(context: Context, position: String) {
        prefs(context).edit().putString(KEY_TIMESTAMP_POSITION, position).apply()
    }

    private const val KEY_TIMESTAMP_SIZE = "timestamp_size"

    fun getTimestampSize(context: Context): String =
        prefs(context).getString(KEY_TIMESTAMP_SIZE, "Medium") ?: "Medium"

    fun setTimestampSize(context: Context, size: String) {
        prefs(context).edit().putString(KEY_TIMESTAMP_SIZE, size).apply()
    }

    // --- Flashlight & Night Mode ---
    private const val KEY_FLASHLIGHT_ENABLED = "flashlight_enabled"
    private const val KEY_NIGHT_MODE_ENABLED = "night_mode_enabled"

    fun getFlashlightEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLASHLIGHT_ENABLED, false)

    fun setFlashlightEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLASHLIGHT_ENABLED, enabled).apply()
    }

    fun getNightModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NIGHT_MODE_ENABLED, false)

    fun setNightModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NIGHT_MODE_ENABLED, enabled).apply()
    }

    // --- Detection ---
    private const val KEY_DETECTION_ENABLED = "detection_enabled"
    private const val KEY_MOTION_DETECTION_ENABLED = "motion_detection_enabled"
    private const val KEY_OBJECT_DETECTION_ENABLED = "object_detection_enabled"

    fun getDetectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DETECTION_ENABLED, false)

    fun setDetectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DETECTION_ENABLED, enabled).apply()
    }

    fun getMotionDetectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MOTION_DETECTION_ENABLED, true)

    fun setMotionDetectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MOTION_DETECTION_ENABLED, enabled).apply()
    }

    fun getObjectDetectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OBJECT_DETECTION_ENABLED, true)

    fun setObjectDetectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OBJECT_DETECTION_ENABLED, enabled).apply()
    }

    // --- Detection tuning ---
    private const val KEY_MOTION_SENSITIVITY = "motion_sensitivity"
    private const val KEY_DETECTION_COOLDOWN_SECONDS = "detection_cooldown_seconds"

    const val DEFAULT_MOTION_SENSITIVITY = 5
    const val DEFAULT_DETECTION_COOLDOWN_SECONDS = 10

    /** 1 (least sensitive) .. 10 (most sensitive). */
    fun getMotionSensitivity(context: Context): Int =
        prefs(context).getInt(KEY_MOTION_SENSITIVITY, DEFAULT_MOTION_SENSITIVITY).coerceIn(1, 10)

    fun setMotionSensitivity(context: Context, sensitivity: Int) {
        prefs(context).edit().putInt(KEY_MOTION_SENSITIVITY, sensitivity.coerceIn(1, 10)).apply()
    }

    /** Minimum gap between two events of the same type, in seconds. */
    fun getDetectionCooldownSeconds(context: Context): Int =
        prefs(context).getInt(KEY_DETECTION_COOLDOWN_SECONDS, DEFAULT_DETECTION_COOLDOWN_SECONDS)
            .coerceIn(1, 600)

    fun setDetectionCooldownSeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(KEY_DETECTION_COOLDOWN_SECONDS, seconds.coerceIn(1, 600))
            .apply()
    }

    // --- Web dashboard security ---
    private const val KEY_WEB_AUTH_ENABLED = "web_auth_enabled"
    private const val KEY_CREDENTIALS_SEEDED = "credentials_seeded"

    /**
     * Whether the dashboard on port 8080 requires HTTP Basic auth.
     *
     * Defaults to true: the dashboard exposes the live camera and every setting, so
     * "open to the whole LAN" is not a safe default. Users who want the old behaviour
     * can turn it off in the app or from the dashboard itself.
     */
    fun getWebAuthEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEB_AUTH_ENABLED, true)

    fun setWebAuthEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEB_AUTH_ENABLED, enabled).apply()
    }

    /**
     * Seeds a username and a strong generated password the first time the app runs with
     * dashboard auth enabled, and returns the generated password so the caller can show
     * it. Returns null if credentials already exist -- without this, enabling auth by
     * default would lock users out of their own camera with no way back in.
     */
    fun seedCredentialsIfMissing(context: Context): String? {
        val preferences = prefs(context)
        if (preferences.getBoolean(KEY_CREDENTIALS_SEEDED, false)) return null
        if (getUsername(context).isNotEmpty() && getPassword(context).isNotEmpty()) {
            preferences.edit().putBoolean(KEY_CREDENTIALS_SEEDED, true).apply()
            return null
        }

        val password = WebAuth.generatePassword(16)
        preferences.edit()
            .putString(KEY_AUTH_USERNAME, "admin")
            .putString(KEY_AUTH_PASSWORD, password)
            .putBoolean(KEY_CREDENTIALS_SEEDED, true)
            .apply()
        return password
    }

    // --- Audio ---
    private const val KEY_AUDIO_ENABLED = "audio_enabled"

    /**
     * Off by default. Enabling it makes the service claim the microphone
     * foreground-service type and require the RECORD_AUDIO grant.
     */
    fun getAudioEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIO_ENABLED, false)

    fun setAudioEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO_ENABLED, enabled).apply()
    }

    // --- Startup behaviour ---
    private const val KEY_START_ON_BOOT = "start_on_boot"
    private const val KEY_AUTO_START_ON_LAUNCH = "auto_start_on_launch"

    /** Off by default: a camera server should not silently start itself after a reboot. */
    fun getStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_START_ON_BOOT, false)

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
    }

    /** Off by default: opening the app should not immediately begin streaming. */
    fun getAutoStartOnLaunch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_ON_LAUNCH, false)

    fun setAutoStartOnLaunch(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_LAUNCH, enabled).apply()
    }

    // --- Advanced settings ---
    private const val KEY_ADVANCED_UNLOCKED = "advanced_unlocked"

    /**
     * Whether the hidden Advanced section is revealed in the app UI.
     *
     * This gates *visibility only*, never behaviour. Every advanced setting has a
     * default that reproduces the previous hard-coded value, so a locked install and a
     * freshly unlocked one stream identically until something is actually changed.
     */
    fun getAdvancedUnlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ADVANCED_UNLOCKED, false)

    fun setAdvancedUnlocked(context: Context, unlocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_ADVANCED_UNLOCKED, unlocked).apply()
    }

    /**
     * Clears every advanced tuning value so the app returns to its stock behaviour.
     *
     * Deliberately leaves [KEY_ADVANCED_UNLOCKED] alone: a user who resets a bad encoder
     * setting almost certainly still wants the section they reset it from.
     */
    fun resetAdvancedSettings(context: Context) {
        val editor = prefs(context).edit()
        ADVANCED_KEYS.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * Every key [resetAdvancedSettings] clears. Kept as one list so a new advanced
     * setting cannot be added without a deliberate decision about the reset path.
     */
    private val ADVANCED_KEYS = listOf(
        KEY_FORCE_SOFTWARE
    )
}
