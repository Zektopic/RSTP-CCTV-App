package com.zektopic.cctvapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Load saved settings from preferences so the service starts
            // with the user's last configuration instead of hardcoded defaults
            val serviceIntent = Intent(context, CctvServerService::class.java).apply {
                putExtra("video_codec", AppPreferences.getVideoCodec(context))
                putExtra("width", AppPreferences.getVideoWidth(context))
                putExtra("height", AppPreferences.getVideoHeight(context))
                putExtra("force_software", AppPreferences.getForceSoftware(context))
                putExtra("show_preview", AppPreferences.getShowPreview(context))
                putExtra("auth_enabled", AppPreferences.getAuthEnabled(context))
                putExtra("auth_username", AppPreferences.getUsername(context))
                putExtra("auth_password", AppPreferences.getPassword(context))
                putExtra("show_timestamp", AppPreferences.getShowTimestamp(context))
                putExtra("show_date", AppPreferences.getShowDate(context))
                putExtra("timestamp_position", AppPreferences.getTimestampPosition(context))
                putExtra("timestamp_size", AppPreferences.getTimestampSize(context))
                putExtra("flashlight_enabled", AppPreferences.getFlashlightEnabled(context))
                putExtra("night_mode_enabled", AppPreferences.getNightModeEnabled(context))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}