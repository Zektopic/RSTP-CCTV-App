package com.zektopic.cctvapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager

import androidx.core.app.NotificationCompat
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import com.pedro.common.VideoCodec
import com.pedro.encoder.video.VideoEncoder
import com.pedro.rtspserver.RtspServerCamera1
import com.pedro.library.view.OpenGlView
import com.pedro.common.ConnectChecker

import android.view.SurfaceHolder

class CctvServerService : Service(), ConnectChecker, SurfaceHolder.Callback {

    private lateinit var rtspServerCamera1: RtspServerCamera1
    private lateinit var openGlView: OpenGlView
    private lateinit var webServer: WebServer
    private lateinit var windowManager: WindowManager
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "CctvServerChannel"
    private var isSurfaceCreated = false
    private var useH265 = false
    private val currentSnapshot = AtomicReference<ByteArray>(null)
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private val snapshotRunnable = object : Runnable {
        override fun run() {
            if (::rtspServerCamera1.isInitialized && rtspServerCamera1.isStreaming) {
                 takeSnapshot()
            }
            snapshotHandler.postDelayed(this, 500) // 2 FPS to be safe
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        openGlView = OpenGlView(applicationContext)
        
        val layoutParams = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(openGlView, layoutParams)
        openGlView.holder.addCallback(this)

        webServer = WebServer(this, getIpAddress()) {
            currentSnapshot.get()
        }
        webServer.start()
        // snapshotHandler.post(snapshotRunnable) // Temporarily disabled to debug crash
    }

    private fun getIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        return String.format("%d.%d.%d.%d",
            (ipAddress and 0xff),
            (ipAddress shr 8 and 0xff),
            (ipAddress shr 16 and 0xff),
            (ipAddress shr 24 and 0xff))
    }

    private fun takeSnapshot() {
        try {
            // RtspServerCamera1/BaseCamera1 doesn't expose glInterface directly in all versions, 
            // but OpenGlView does.
            openGlView.takePhoto { bitmap -> 
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                currentSnapshot.set(stream.toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        useH265 = intent?.getBooleanExtra("use_h265", false) ?: false

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CCTV Server")
            .setContentText("Server is running.")
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (::rtspServerCamera1.isInitialized && rtspServerCamera1.isStreaming) {
            rtspServerCamera1.stopStream()
        }
        
        if (isSurfaceCreated) {
            startStream()
        }

        return START_STICKY
    }

    private fun startStream() {
        if (!isSurfaceCreated || !openGlView.holder.surface.isValid) return
        
        try {
            if (!::rtspServerCamera1.isInitialized) {
                rtspServerCamera1 = RtspServerCamera1(openGlView, this, 8554)
            }
            
            if (!rtspServerCamera1.isStreaming) {
                rtspServerCamera1.prepareAudio(64 * 1024, 44100, true, false, false)
                rtspServerCamera1.setVideoCodec(if (useH265) VideoCodec.H265 else VideoCodec.H264)
                rtspServerCamera1.prepareVideo(640, 480, 30, 2000 * 1024, 0)
                rtspServerCamera1.startStream()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceCreated = true
        startStream()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (::rtspServerCamera1.isInitialized && !rtspServerCamera1.isStreaming) {
             startStream()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
        if (::rtspServerCamera1.isInitialized && rtspServerCamera1.isStreaming) {
            rtspServerCamera1.stopStream()
        }


    }

    override fun onDestroy() {
        super.onDestroy()
        if (::rtspServerCamera1.isInitialized && rtspServerCamera1.isStreaming) {
            rtspServerCamera1.stopStream()
        }
        if (::openGlView.isInitialized) {
            windowManager.removeView(openGlView)
        }
        webServer.stop()
        snapshotHandler.removeCallbacks(snapshotRunnable)
        stopForeground(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "CCTV Server Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    // ConnectChecker methods
    override fun onConnectionStarted(url: String) {
        android.util.Log.d("CctvServerService", "Connection started: $url")
    }

    override fun onConnectionSuccess() {
        android.util.Log.d("CctvServerService", "Connection success")
    }

    override fun onConnectionFailed(reason: String) {
        android.util.Log.e("CctvServerService", "Connection failed: $reason")
    }

    override fun onNewBitrate(bitrate: Long) {
        android.util.Log.d("CctvServerService", "New bitrate: $bitrate")
    }

    override fun onDisconnect() {
        android.util.Log.d("CctvServerService", "Disconnected")
    }

    override fun onAuthError() {
        android.util.Log.e("CctvServerService", "Auth error")
    }

    override fun onAuthSuccess() {
        android.util.Log.d("CctvServerService", "Auth success")
    }
}