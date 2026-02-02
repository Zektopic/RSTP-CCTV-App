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
import com.pedro.rtspserver.RtspServerCamera2
import com.pedro.library.view.OpenGlView
import com.pedro.common.ConnectChecker
import android.media.MediaCodecList
import android.media.MediaFormat

import android.view.SurfaceHolder

class CctvServerService : Service(), ConnectChecker, SurfaceHolder.Callback {

    private lateinit var rtspServerCamera: RtspServerCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var webServer: WebServer
    private lateinit var windowManager: WindowManager
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "CctvServerChannel"
    private var isSurfaceCreated = false
    private var videoWidth = 640
    private var videoHeight = 480
    private var useH265 = false
    private var showPreview = false
    private val currentSnapshot = AtomicReference<ByteArray>(null)
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private val snapshotRunnable = object : Runnable {
        override fun run() {
            if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
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
        openGlView.holder.setFixedSize(640, 480) // Ensure valid surface size

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
            onCodecUpdate = { enableH265 ->
                if (useH265 != enableH265) {
                    useH265 = enableH265
                    // Restart stream with new codec if running
                    if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
                        rtspServerCamera.stopStream()
                        startStream()
                    }
                }
            },
            isH265 = { useH265 },
            onResolutionUpdate = { w, h ->
                if (videoWidth != w || videoHeight != h) {
                    videoWidth = w
                    videoHeight = h
                    // Restart stream with new resolution if running
                    if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
                        rtspServerCamera.stopStream()
                        startStream()
                    }
                }
            }
        )
        webServer.start()
        snapshotHandler.post(snapshotRunnable)
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
            // RtspServerCamera2/BaseCamera2 approach for snapshots via OpenGlView
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

        val newUseH265 = intent?.getBooleanExtra("use_h265", false) ?: false
        val newShowPreview = intent?.getBooleanExtra("show_preview", false) ?: false
        val newWidth = intent?.getIntExtra("width", 640) ?: 640
        val newHeight = intent?.getIntExtra("height", 480) ?: 480

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CCTV Server")
            .setContentText("Server is running.")
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            if (useH265 != newUseH265 || videoWidth != newWidth || videoHeight != newHeight) {
                rtspServerCamera.stopStream()
                // Proceed to start stream with new config
            } else {
                // Already streaming with correct config, ignore
                if (showPreview != newShowPreview) {
                     showPreview = newShowPreview
                     updateOverlaySize()
                }
                return START_STICKY
            }
        }
        
        useH265 = newUseH265
        videoWidth = newWidth
        videoHeight = newHeight
        
        if (showPreview != newShowPreview) {
             showPreview = newShowPreview
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
                // 1080p (2MP) -> 6 Mbps
                // 720p (0.9MP) -> 4 Mbps
                // 480p (0.3MP) -> 2 Mbps
                val bitrate = when {
                    videoWidth >= 1920 -> 6000 * 1024
                    videoWidth >= 1280 -> 4000 * 1024
                    else -> 2000 * 1024
                }

                rtspServerCamera.prepareAudio(64 * 1024, 44100, true, false, false)

                // Check and set Codec
                var selectedCodec = if (useH265 && isH265Supported()) VideoCodec.H265 else VideoCodec.H264
                rtspServerCamera.setVideoCodec(selectedCodec)

                if (rtspServerCamera.prepareVideo(videoWidth, videoHeight, 30, bitrate, 0)) {
                    rtspServerCamera.startStream()
                } else {
                    // unexpected failure, try fallback if we were trying H265
                    if (selectedCodec == VideoCodec.H265) {
                        android.util.Log.w("CctvServerService", "H265 preparation failed, falling back to H264")
                        rtspServerCamera.setVideoCodec(VideoCodec.H264)
                        if (rtspServerCamera.prepareVideo(videoWidth, videoHeight, 30, bitrate, 0)) {
                             rtspServerCamera.startStream()
                             // Force disable H265 flag so UI knows?
                             // useH265 = false // Optional: update state to reflect reality
                        } else {
                             android.util.Log.e("CctvServerService", "H264 fallback preparation also failed.")
                        }
                    } else {
                        android.util.Log.e("CctvServerService", "Video preparation failed for H264.")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isH265Supported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            val codecs = list.codecInfos
            for (codec in codecs) {
                if (!codec.isEncoder) continue
                val types = codec.supportedTypes
                for (type in types) {
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
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
        if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
            rtspServerCamera.stopStream()
        }
        if (::openGlView.isInitialized) {
            windowManager.removeView(openGlView)
        }
        webServer.stop()
        snapshotHandler.removeCallbacks(snapshotRunnable)
        stopForeground(true)
    }

    private fun updateOverlaySize() {
        if (!::openGlView.isInitialized) return
        
        val layoutParams = openGlView.layoutParams as WindowManager.LayoutParams
        if (showPreview) {
             // Show resized preview, e.g. 320x240 or aspect ratio
             layoutParams.width = 320
             layoutParams.height = 240
        } else {
             // "Hide" it by making it 1x1 pixel
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