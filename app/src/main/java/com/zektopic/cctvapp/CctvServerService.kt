package com.zektopic.cctvapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class CctvServerService : Service(), ConnectChecker, SurfaceHolder.Callback {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "CctvServerChannel"
    }

    private lateinit var rtspServerCamera: RtspServerCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var webServer: WebServer
    private lateinit var windowManager: WindowManager
    private var isSurfaceCreated = false
    private var videoWidth = 640
    private var videoHeight = 480
    private var videoCodec = "H264"
    private var forceSoftware = false
    private var showPreview = false
    private val currentSnapshot = AtomicReference<ByteArray>(null)
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private val snapshotRunnable = object : Runnable {
        override fun run() {
            if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming && isSurfaceCreated) {
                 takeSnapshot()
            }
            snapshotHandler.postDelayed(this, 500) // 2 FPS to be safe
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Load saved settings as defaults
        videoCodec = AppPreferences.getVideoCodec(this)
        videoWidth = AppPreferences.getVideoWidth(this)
        videoHeight = AppPreferences.getVideoHeight(this)
        forceSoftware = AppPreferences.getForceSoftware(this)
        showPreview = AppPreferences.getShowPreview(this)
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        openGlView = OpenGlView(applicationContext)
        
        @Suppress("DEPRECATION")
        val layoutParams = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(openGlView, layoutParams)
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to add view. Permission issue?", e)
            e.printStackTrace()
        }
        openGlView.holder.addCallback(this)
        openGlView.holder.setFixedSize(640, 480)

        webServer = WebServer(this, getIpAddress(), 
            imageProvider = { currentSnapshot.get() },
            onSwitchCamera = {
                if (::rtspServerCamera.isInitialized) {
                    try {
                        rtspServerCamera.switchCamera()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            },
            onStartStream = {
                if (isSurfaceCreated && (!::rtspServerCamera.isInitialized || !rtspServerCamera.isStreaming)) {
                    startStream()
                }
            },
            onStopStream = {
                if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
                    rtspServerCamera.stopStream()
                }
            },
            isStreaming = {
                if (::rtspServerCamera.isInitialized) rtspServerCamera.isStreaming else false
            },
            onCodecUpdate = { newCodec ->
                if (videoCodec != newCodec) {
                    videoCodec = newCodec
                    AppPreferences.setVideoCodec(this, newCodec)
                    if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
                        rtspServerCamera.stopStream()
                        startStream()
                    }
                }
            },
            getCurrentCodec = { videoCodec },
            onResolutionUpdate = { w, h ->
                if (videoWidth != w || videoHeight != h) {
                    videoWidth = w
                    videoHeight = h
                    AppPreferences.setResolution(this, w, h)
                    if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
                        rtspServerCamera.stopStream()
                        startStream()
                    }
                }
            },
            getCurrentResolution = { "${videoWidth}x${videoHeight}" }
        )
        webServer.start()
        snapshotHandler.post(snapshotRunnable)
    }

    private fun getIpAddress(): String {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        val ipv4Address = linkProperties?.linkAddresses?.firstOrNull { 
            it.address is Inet4Address && !it.address.isLoopbackAddress 
        }?.address?.hostAddress
        return ipv4Address ?: "0.0.0.0"
    }

    private fun takeSnapshot() {
        if (!isSurfaceCreated || !::openGlView.isInitialized || !openGlView.holder.surface.isValid) return
        try {
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
        if (intent?.action == "ACTION_SWITCH_CAMERA") {
            if (::rtspServerCamera.isInitialized) {
                try {
                    rtspServerCamera.switchCamera()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return START_STICKY
        }

        if (intent?.action == "ACTION_TOGGLE_PREVIEW") {
            val show = intent.getBooleanExtra("show_preview", false)
            if (showPreview != show) {
                showPreview = show
                updateOverlaySize()
            }
            return START_STICKY
        }

        val newVideoCodec = intent?.getStringExtra("video_codec") ?: AppPreferences.getVideoCodec(this)
        val newShowPreview = intent?.getBooleanExtra("show_preview", AppPreferences.getShowPreview(this)) ?: false
        val newWidth = intent?.getIntExtra("width", AppPreferences.getVideoWidth(this)) ?: 640
        val newHeight = intent?.getIntExtra("height", AppPreferences.getVideoHeight(this)) ?: 480
        val newForceSoftware = intent?.getBooleanExtra("force_software", AppPreferences.getForceSoftware(this)) ?: false

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CCTV Server")
            .setContentText("Server is running.")
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Initialize wrapper if needed
        if (!::rtspServerCamera.isInitialized) {
             rtspServerCamera = RtspServerCamera2(openGlView, this, 8554)
        }

        // If already streaming, check if we need to restart due to config change
        if (rtspServerCamera.isStreaming) {
            if (videoCodec != newVideoCodec || videoWidth != newWidth || videoHeight != newHeight || forceSoftware != newForceSoftware) {
                rtspServerCamera.stopStream()
            } else {
                if (showPreview != newShowPreview) {
                     showPreview = newShowPreview
                     updateOverlaySize()
                }
                return START_STICKY
            }
        }
        
        videoCodec = newVideoCodec
        videoWidth = newWidth
        videoHeight = newHeight
        forceSoftware = newForceSoftware
        
        // Persist the settings
        AppPreferences.setVideoCodec(this, videoCodec)
        AppPreferences.setResolution(this, videoWidth, videoHeight)
        AppPreferences.setForceSoftware(this, forceSoftware)

        if (showPreview != newShowPreview) {
             showPreview = newShowPreview
             AppPreferences.setShowPreview(this, showPreview)
             updateOverlaySize()
        }

        if (isSurfaceCreated) {
            startStream()
        }

        return START_STICKY
    }

    private fun startStream() {
        if (!isSurfaceCreated || !openGlView.holder.surface.isValid) return
        
        try {
            if (!::rtspServerCamera.isInitialized) {
                rtspServerCamera = RtspServerCamera2(openGlView, this, 8554)
            }
            
            if (!rtspServerCamera.isStreaming) {
                // Dynamic Bitrate Calculation
                val bitrate = when {
                    videoWidth >= 1920 -> 6000 * 1024
                    videoWidth >= 1280 -> 4000 * 1024
                    else -> 2000 * 1024
                }

                rtspServerCamera.prepareAudio(64 * 1024, 44100, true, false, false)

                // Check and set Codec
                val selectedCodec = when (videoCodec) {
                    "H265" -> VideoCodec.H265
                    "AV1" -> VideoCodec.AV1
                    "VP9" -> {
                         android.util.Log.w("CctvServerService", "VP9 codec constant missing, falling back to H264")
                         VideoCodec.H264
                    }
                    else -> VideoCodec.H264
                }
                
                rtspServerCamera.setVideoCodec(selectedCodec)
                android.util.Log.d("CctvServerService", "Selected codec: $selectedCodec ($videoCodec)")

                if (rtspServerCamera.prepareVideo(videoWidth, videoHeight, 30, bitrate, 0)) {
                    rtspServerCamera.startStream()
                } else {
                    android.util.Log.w("CctvServerService", "Codec $selectedCodec preparation failed, falling back to H264")
                    rtspServerCamera.setVideoCodec(VideoCodec.H264)
                    if (rtspServerCamera.prepareVideo(videoWidth, videoHeight, 30, bitrate, 0)) {
                         rtspServerCamera.startStream()
                         videoCodec = "H264"
                         AppPreferences.setVideoCodec(this, videoCodec)
                    } else {
                         android.util.Log.e("CctvServerService", "H264 fallback preparation also failed.")
                    }
                }
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
        if (::rtspServerCamera.isInitialized && !rtspServerCamera.isStreaming) {
             startStream()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
        if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
            rtspServerCamera.stopStream()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        snapshotHandler.removeCallbacks(snapshotRunnable)
        
        webServer.stop()
        
        if (::rtspServerCamera.isInitialized) { 
            try {
                if (rtspServerCamera.isStreaming) {
                    rtspServerCamera.stopStream()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        if (::openGlView.isInitialized) {
            try {
                windowManager.removeView(openGlView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun updateOverlaySize() {
        if (!::openGlView.isInitialized) return
        
        val layoutParams = openGlView.layoutParams as WindowManager.LayoutParams
        if (showPreview) {
             layoutParams.width = 320
             layoutParams.height = 240
        } else {
             layoutParams.width = 1
             layoutParams.height = 1
        }
        windowManager.updateViewLayout(openGlView, layoutParams)
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