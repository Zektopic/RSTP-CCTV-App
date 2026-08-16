package com.zektopic.cctvapp

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Restarts the camera server after a reboot, when the user has asked for that.
 *
 * Android 14 and later refuse to let a background component start a foreground service
 * whose type is `camera` or `microphone` -- the start throws
 * `ForegroundServiceStartNotAllowedException`. An uncaught throw here crashes the
 * receiver, so the start is guarded and degrades to a tap-to-resume notification.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val RESUME_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "CctvServerChannel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Opt-in only. Silently re-arming a camera after every reboot is not a
        // reasonable default for a device that might have changed hands or location.
        if (!AppPreferences.getStartOnBoot(context)) {
            Log.d(TAG, "Start-on-boot disabled; ignoring BOOT_COMPLETED")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Camera permission not granted; not starting server on boot")
            return
        }

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
            putExtra("detection_enabled", AppPreferences.getDetectionEnabled(context))
            putExtra("motion_detection_enabled", AppPreferences.getMotionDetectionEnabled(context))
            putExtra("object_detection_enabled", AppPreferences.getObjectDetectionEnabled(context))
            putExtra("audio_enabled", AppPreferences.getAudioEnabled(context))
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Camera server started after boot")
        } catch (e: Exception) {
            // Covers ForegroundServiceStartNotAllowedException (API 31+) without
            // referencing a class that does not exist on older platforms, plus the
            // SecurityException some OEM builds throw instead.
            Log.w(TAG, "Could not start server on boot; prompting the user instead", e)
            notifyResumeRequired(context)
        }
    }

    /** Falls back to a notification the user can tap to start the server by hand. */
    private fun notifyResumeRequired(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cctv)
            .setContentTitle(context.getString(R.string.boot_resume_title))
            .setContentText(context.getString(R.string.boot_resume_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            manager.notify(RESUME_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Could not post resume notification", e)
        }
    }
}
