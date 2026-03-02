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
    private const val KEY_USE_BACK_CAMERA = "use_back_camera"

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

    // --- Camera Selection ---
    fun getUseBackCamera(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_BACK_CAMERA, true)

    fun setUseBackCamera(context: Context, useBack: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_BACK_CAMERA, useBack).apply()
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
}
