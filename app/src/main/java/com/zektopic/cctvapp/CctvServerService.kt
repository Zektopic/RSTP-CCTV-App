package com.zektopic.cctvapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
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
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class CctvServerService : Service(), ConnectChecker, SurfaceHolder.Callback {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "CctvServerChannel"
        const val ACTION_STOP_SERVER = "ACTION_STOP_SERVER"

        /** Capture cadence while detection is on or the dashboard is open. */
        private const val ACTIVE_SNAPSHOT_INTERVAL_MS = 500L
        /** Heartbeat while nothing needs frames -- just enough to notice a new viewer. */
        private const val IDLE_SNAPSHOT_INTERVAL_MS = 3000L
        /** How long after the last /shot.jpg we keep treating a viewer as present. */
        private const val VIEWER_IDLE_TIMEOUT_MS = 10_000L

        /** COCO labels treated as an "animal" event. */
        private val ANIMAL_LABELS = setOf("cat", "dog", "bird", "horse", "sheep", "cow")
    }

    private lateinit var rtspServerCamera: RtspServerCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var webServer: WebServer
    private lateinit var windowManager: WindowManager
    private var isSurfaceCreated = false

    // These are read and written from both the main thread and NanoHTTPD worker
    // threads, so every one of them has to be @Volatile.
    @Volatile private var videoWidth = 640
    @Volatile private var videoHeight = 480
    @Volatile private var videoCodec = "H264"
    @Volatile private var forceSoftware = false
    @Volatile private var showPreview = false
    @Volatile private var authEnabled = false
    @Volatile private var authUsername = ""
    @Volatile private var authPassword = ""
    @Volatile private var webAuthEnabled = true
    @Volatile private var audioEnabled = false
    @Volatile private var showTimestamp = false
    @Volatile private var showDate = false
    @Volatile private var timestampPosition = "Top Left"
    @Volatile private var timestampSize = "Medium"
    @Volatile private var flashlightEnabled = false
    @Volatile private var nightModeEnabled = false
    @Volatile private var detectionEnabled = false
    @Volatile private var motionDetectionEnabled = true
    @Volatile private var objectDetectionEnabled = true
    private var isLanternOn = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Runs [block] on the main thread.
     *
     * Every WebServer callback arrives on a NanoHTTPD worker thread, but touching the
     * camera or the overlay view off the main thread throws (`updateViewLayout` raises
     * CalledFromWrongThreadException). Only the *side effects* are posted -- the state
     * fields themselves are assigned synchronously by the caller, so a request that
     * changes a setting still reflects the new value by the time it responds.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var textFilter: TextObjectFilterRender? = null
    private lateinit var eventStore: EventStore
    private val retentionHandler = Handler(Looper.getMainLooper())
    private val retentionRunnable = object : Runnable {
        override fun run() {
            try {
                eventStore.cleanupExpired()
            } catch (e: Exception) {
                android.util.Log.e("CctvServerService", "Retention cleanup failed", e)
            }
            retentionHandler.postDelayed(this, 60 * 60 * 1000L)
        }
    }
    private val timestampHandler = Handler(Looper.getMainLooper())
    private val timestampRunnable = object : Runnable {
        override fun run() {
            updateTimestampText()
            timestampHandler.postDelayed(this, 1000)
        }
    }
    private val currentSnapshot = AtomicReference<ByteArray>(null)
    private val detectionExecutor = Executors.newSingleThreadExecutor()
    private val motionDetector = MotionDetector()
    private lateinit var liteRtObjectDetector: LiteRtObjectDetector
    private val lastEventMsByType = mutableMapOf<String, Long>()
    private val snapshotHandler = Handler(Looper.getMainLooper())

    /** When a dashboard client last asked for /shot.jpg, for idle throttling. */
    @Volatile private var lastSnapshotRequestMs = 0L

    private val snapshotRunnable = object : Runnable {
        override fun run() {
            val streaming = ::rtspServerCamera.isInitialized &&
                rtspServerCamera.isStreaming && isSurfaceCreated

            // Capturing + JPEG-encoding twice a second around the clock is the single
            // biggest battery cost in the app, and most of the time nothing consumes the
            // result. Only run at full rate when detection needs frames or somebody is
            // watching the dashboard; otherwise idle at a slow heartbeat.
            val viewerActive =
                System.currentTimeMillis() - lastSnapshotRequestMs < VIEWER_IDLE_TIMEOUT_MS
            val wanted = streaming && (detectionEnabled || viewerActive)

            if (wanted) takeSnapshot()

            snapshotHandler.postDelayed(this, if (wanted) ACTIVE_SNAPSHOT_INTERVAL_MS else IDLE_SNAPSHOT_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        eventStore = EventStore.forContext(this)
        eventStore.cleanupExpired()

        // Load saved settings as defaults
        videoCodec = AppPreferences.getVideoCodec(this)
        videoWidth = AppPreferences.getVideoWidth(this)
        videoHeight = AppPreferences.getVideoHeight(this)
        forceSoftware = AppPreferences.getForceSoftware(this)
        showPreview = AppPreferences.getShowPreview(this)
        authEnabled = AppPreferences.getAuthEnabled(this)
        authUsername = AppPreferences.getUsername(this)
        authPassword = AppPreferences.getPassword(this)
        showTimestamp = AppPreferences.getShowTimestamp(this)
        showDate = AppPreferences.getShowDate(this)
        timestampPosition = AppPreferences.getTimestampPosition(this)
        timestampSize = AppPreferences.getTimestampSize(this)
        flashlightEnabled = AppPreferences.getFlashlightEnabled(this)
        nightModeEnabled = AppPreferences.getNightModeEnabled(this)
        detectionEnabled = AppPreferences.getDetectionEnabled(this)
        motionDetectionEnabled = AppPreferences.getMotionDetectionEnabled(this)
        objectDetectionEnabled = AppPreferences.getObjectDetectionEnabled(this)
        webAuthEnabled = AppPreferences.getWebAuthEnabled(this)
        audioEnabled = AppPreferences.getAudioEnabled(this)
        motionDetector.updateSensitivity(AppPreferences.getMotionSensitivity(this))
        liteRtObjectDetector = LiteRtObjectDetector(this)

        // Setup light sensor for night mode
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        
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
            imageProvider = {
                // Record the demand so the capture loop keeps running while somebody is
                // actually watching, and kick it immediately -- otherwise the first frame
                // after an idle period comes back as "Camera not ready".
                lastSnapshotRequestMs = System.currentTimeMillis()
                if (currentSnapshot.get() == null) {
                    onMain { takeSnapshot() }
                }
                currentSnapshot.get()
            },
            onSwitchCamera = {
                onMain {
                    if (::rtspServerCamera.isInitialized) {
                        try {
                            rtspServerCamera.switchCamera()
                        } catch (e: Exception) {
                            android.util.Log.e("CctvServerService", "switchCamera failed", e)
                        }
                    }
                }
            },
            onStartStream = {
                onMain {
                    if (isSurfaceCreated && (!::rtspServerCamera.isInitialized || !rtspServerCamera.isStreaming)) {
                        startStream()
                    }
                }
            },
            onStopStream = {
                onMain {
                    if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
                        rtspServerCamera.stopStream()
                    }
                }
            },
            isStreaming = {
                if (::rtspServerCamera.isInitialized) rtspServerCamera.isStreaming else false
            },
            onCodecUpdate = { newCodec ->
                if (videoCodec != newCodec) {
                    videoCodec = newCodec
                    AppPreferences.setVideoCodec(this, newCodec)
                    onMain { restartStreamIfRunning() }
                }
            },
            getCurrentCodec = { videoCodec },
            onResolutionUpdate = { w, h ->
                if (videoWidth != w || videoHeight != h) {
                    videoWidth = w
                    videoHeight = h
                    AppPreferences.setResolution(this, w, h)
                    onMain { restartStreamIfRunning() }
                }
            },
            getCurrentResolution = { 
                if (videoWidth == 0 && videoHeight == 0) "0x0" else "${videoWidth}x${videoHeight}"
            },
            getAuthEnabled = { authEnabled },
            getUsername = { authUsername },
            getPassword = { authPassword },
            // Each branch assigns and persists synchronously on the calling thread, then
            // posts only the camera/view work. That ordering matters: the dashboard polls
            // /status immediately after a change, and an async assignment would make the
            // toggle snap back to its old value.
            onSettingUpdate = { key, value ->
                when (key) {
                    "show_timestamp" -> {
                        showTimestamp = value.toBoolean()
                        AppPreferences.setShowTimestamp(this, showTimestamp)
                        onMain { applyTimestampOverlay() }
                    }
                    "show_date" -> {
                        showDate = value.toBoolean()
                        AppPreferences.setShowDate(this, showDate)
                        onMain { applyTimestampOverlay() }
                    }
                    "timestamp_position" -> {
                        timestampPosition = value
                        AppPreferences.setTimestampPosition(this, timestampPosition)
                        onMain { applyTimestampOverlay() }
                    }
                    "timestamp_size" -> {
                        timestampSize = value
                        AppPreferences.setTimestampSize(this, timestampSize)
                        onMain { applyTimestampOverlay() }
                    }
                    "flashlight_enabled" -> {
                        flashlightEnabled = value.toBoolean()
                        AppPreferences.setFlashlightEnabled(this, flashlightEnabled)
                        onMain { applyFlashlight() }
                    }
                    "night_mode_enabled" -> {
                        nightModeEnabled = value.toBoolean()
                        AppPreferences.setNightModeEnabled(this, nightModeEnabled)
                        onMain { updateNightModeSensor() }
                    }
                    "force_software" -> {
                        forceSoftware = value.toBoolean()
                        AppPreferences.setForceSoftware(this, forceSoftware)
                        onMain { restartStreamIfRunning() }
                    }
                    "show_preview" -> {
                        showPreview = value.toBoolean()
                        AppPreferences.setShowPreview(this, showPreview)
                        onMain { updateOverlaySize() }
                    }
                    "audio_enabled" -> {
                        audioEnabled = value.toBoolean()
                        AppPreferences.setAudioEnabled(this, audioEnabled)
                        onMain { restartStreamIfRunning() }
                    }
                    "web_auth_enabled" -> {
                        webAuthEnabled = value.toBoolean()
                        AppPreferences.setWebAuthEnabled(this, webAuthEnabled)
                    }
                    "detection_enabled" -> {
                        detectionEnabled = value.toBoolean()
                        AppPreferences.setDetectionEnabled(this, detectionEnabled)
                    }
                    "motion_detection_enabled" -> {
                        motionDetectionEnabled = value.toBoolean()
                        AppPreferences.setMotionDetectionEnabled(this, motionDetectionEnabled)
                    }
                    "object_detection_enabled" -> {
                        objectDetectionEnabled = value.toBoolean()
                        AppPreferences.setObjectDetectionEnabled(this, objectDetectionEnabled)
                    }
                    "motion_sensitivity" -> {
                        value.toIntOrNull()?.let { sensitivity ->
                            AppPreferences.setMotionSensitivity(this, sensitivity)
                            motionDetector.updateSensitivity(sensitivity)
                        }
                    }
                    "detection_cooldown_seconds" -> {
                        value.toIntOrNull()?.let { seconds ->
                            AppPreferences.setDetectionCooldownSeconds(this, seconds)
                        }
                    }
                }
            },
            getShowTimestamp = { showTimestamp },
            getShowDate = { showDate },
            getTimestampPosition = { timestampPosition },
            getTimestampSize = { timestampSize },
            getFlashlightEnabled = { flashlightEnabled },
            getNightModeEnabled = { nightModeEnabled },
            getForceSoftware = { forceSoftware },
            getShowPreview = { showPreview },
            getDetectionEnabled = { detectionEnabled },
            getMotionDetectionEnabled = { motionDetectionEnabled },
            getObjectDetectionEnabled = { objectDetectionEnabled },
            getObjectDetectorReady = { liteRtObjectDetector.isReady() },
            onAuthUpdate = { enabled, username, password ->
                authEnabled = enabled
                authUsername = username
                authPassword = password
                AppPreferences.setAuthEnabled(this, enabled)
                AppPreferences.setUsername(this, username)
                AppPreferences.setPassword(this, password)
                onMain {
                    if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
                        if (enabled && username.isNotEmpty() && password.isNotEmpty()) {
                            rtspServerCamera.getStreamClient().setAuthorization(username, password)
                        } else {
                            rtspServerCamera.getStreamClient().setAuthorization("", "")
                        }
                    }
                }
            },
            listEventsJson = { sinceMs, limit -> eventStore.listEventsAsJson(sinceMs, limit) },
            getEventJson = { id -> eventStore.getEventAsJson(id) },
            getEventSnapshotFile = { id -> eventStore.getEventSnapshotFile(id) },
            getEventClipFile = { id -> eventStore.getEventClipFile(id) },
            onCreateTestEvent = {
                val event = eventStore.createTestEvent(currentSnapshot.get())
                event.toJsonObject().toString()
            },
            getBatteryLevel = { getBatteryLevel() },
            getWifiStrength = { getWifiStrength() },
            getWebAuthEnabled = { webAuthEnabled }
        )
        webServer.start()
        snapshotHandler.post(snapshotRunnable)
        retentionHandler.post(retentionRunnable)
    }

    /** Restarts an in-flight stream so a changed encoder setting takes effect. Main thread only. */
    private fun restartStreamIfRunning() {
        if (::rtspServerCamera.isInitialized && rtspServerCamera.isStreaming) {
            rtspServerCamera.stopStream()
            startStream()
        }
    }

    private fun hasPermission(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Battery percentage, or -1 if unavailable.
     *
     * Uses BatteryManager directly rather than a sticky-broadcast registration: /status
     * is polled every few seconds by every connected dashboard, and registering a
     * receiver per request is needless work.
     */
    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (capacity in 0..100) capacity else -1
    }

    /**
     * Wi-Fi signal as a percentage, or -1 when it cannot be determined.
     *
     * `WifiManager.connectionInfo` is deprecated and, on modern Android, returns a
     * placeholder RSSI to apps without location permission. Reporting -1 (rendered as
     * a dash) is honest; reporting a number derived from -127 is not.
     */
    @Suppress("DEPRECATION")
    private fun getWifiStrength(): Int {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return -1
            val rssi = wifiManager.connectionInfo?.rssi ?: return -1
            if (rssi == Int.MIN_VALUE || rssi <= -127) return -1
            WifiManager.calculateSignalLevel(rssi, 100).coerceIn(0, 100)
        } catch (e: Exception) {
            android.util.Log.w("CctvServerService", "Wi-Fi strength unavailable", e)
            -1
        }
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
                val jpeg = stream.toByteArray()
                currentSnapshot.set(jpeg)
                runDetectionPipelineIfEnabled(jpeg)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun runDetectionPipelineIfEnabled(snapshotJpeg: ByteArray) {
        if (!detectionEnabled || (!motionDetectionEnabled && !objectDetectionEnabled)) return

        detectionExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                bitmap = BitmapFactory.decodeByteArray(snapshotJpeg, 0, snapshotJpeg.size)
                    ?: return@execute

                if (motionDetectionEnabled) {
                    val motion = motionDetector.isMotionDetected(bitmap)
                    if (motion.first) {
                        maybeCreateDetectionEvent("motion", motion.second, snapshotJpeg)
                    }
                }

                if (objectDetectionEnabled) {
                    val detections = liteRtObjectDetector.detect(bitmap)

                    detections.filter { it.label == "person" }.maxByOrNull { it.score }?.let {
                        maybeCreateDetectionEvent("person", it.score.toDouble(), snapshotJpeg)
                    }

                    detections
                        .filter { it.label in ANIMAL_LABELS }
                        .maxByOrNull { it.score }
                        ?.let { maybeCreateDetectionEvent("animal", it.score.toDouble(), snapshotJpeg) }
                }
            } catch (e: Exception) {
                android.util.Log.e("CctvServerService", "Detection pipeline failed", e)
            } finally {
                // Decoded once per frame at up to 2 FPS -- without this the GC churn is
                // significant and shows up as dropped frames on low-end devices.
                bitmap?.recycle()
            }
        }
    }

    private fun maybeCreateDetectionEvent(type: String, score: Double, snapshotJpeg: ByteArray) {
        val now = System.currentTimeMillis()
        val lastAt = lastEventMsByType[type] ?: 0L
        val cooldownMs = AppPreferences.getDetectionCooldownSeconds(this) * 1000L
        if (now - lastAt < cooldownMs) return

        lastEventMsByType[type] = now
        eventStore.createDetectionEvent(type, score, snapshotJpeg)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVER) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "ACTION_SET_SETTING") {
            val key = intent.getStringExtra("setting_key")
            val value = intent.getStringExtra("setting_value")
            if (key == "web_auth_enabled" && value != null) {
                webAuthEnabled = value.toBoolean()
                AppPreferences.setWebAuthEnabled(this, webAuthEnabled)
            }
            return START_STICKY
        }

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

        if (intent?.action == "ACTION_TOGGLE_FLASHLIGHT") {
            flashlightEnabled = intent.getBooleanExtra("flashlight_enabled", false)
            AppPreferences.setFlashlightEnabled(this, flashlightEnabled)
            applyFlashlight()
            return START_STICKY
        }

        if (intent?.action == "ACTION_TOGGLE_NIGHT_MODE") {
            nightModeEnabled = intent.getBooleanExtra("night_mode_enabled", false)
            AppPreferences.setNightModeEnabled(this, nightModeEnabled)
            updateNightModeSensor()
            return START_STICKY
        }

        val newVideoCodec = intent?.getStringExtra("video_codec") ?: AppPreferences.getVideoCodec(this)
        val newShowPreview = intent?.getBooleanExtra("show_preview", AppPreferences.getShowPreview(this)) ?: false
        val newWidth = intent?.getIntExtra("width", AppPreferences.getVideoWidth(this)) ?: 640
        val newHeight = intent?.getIntExtra("height", AppPreferences.getVideoHeight(this)) ?: 480
        val newForceSoftware = intent?.getBooleanExtra("force_software", AppPreferences.getForceSoftware(this)) ?: false
        val newAuthEnabled = intent?.getBooleanExtra("auth_enabled", AppPreferences.getAuthEnabled(this)) ?: false
        val newAuthUsername = intent?.getStringExtra("auth_username") ?: AppPreferences.getUsername(this)
        val newAuthPassword = intent?.getStringExtra("auth_password") ?: AppPreferences.getPassword(this)
        val newShowTimestamp = intent?.getBooleanExtra("show_timestamp", AppPreferences.getShowTimestamp(this)) ?: false
        val newShowDate = intent?.getBooleanExtra("show_date", AppPreferences.getShowDate(this)) ?: false
        val newTimestampPosition = intent?.getStringExtra("timestamp_position") ?: AppPreferences.getTimestampPosition(this)
        val newTimestampSize = intent?.getStringExtra("timestamp_size") ?: AppPreferences.getTimestampSize(this)
        val newDetectionEnabled = intent?.getBooleanExtra("detection_enabled", AppPreferences.getDetectionEnabled(this)) ?: false
        val newMotionDetectionEnabled = intent?.getBooleanExtra("motion_detection_enabled", AppPreferences.getMotionDetectionEnabled(this)) ?: true
        val newObjectDetectionEnabled = intent?.getBooleanExtra("object_detection_enabled", AppPreferences.getObjectDetectionEnabled(this)) ?: true
        audioEnabled = intent?.getBooleanExtra("audio_enabled", AppPreferences.getAudioEnabled(this))
            ?: AppPreferences.getAudioEnabled(this)
        AppPreferences.setAudioEnabled(this, audioEnabled)
        motionDetector.updateSensitivity(AppPreferences.getMotionSensitivity(this))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The declared type must cover every restricted resource the service touches.
            // Recording audio under a camera-only type throws SecurityException on
            // Android 14+, which is why the microphone bit is added whenever audio is on.
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (audioEnabled) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, buildNotification(), serviceType)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
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
                // Update overlay settings even without full restart
                showTimestamp = newShowTimestamp
                showDate = newShowDate
                timestampPosition = newTimestampPosition
                timestampSize = newTimestampSize
                AppPreferences.setShowTimestamp(this, showTimestamp)
                AppPreferences.setShowDate(this, showDate)
                AppPreferences.setTimestampPosition(this, timestampPosition)
                AppPreferences.setTimestampSize(this, timestampSize)
                detectionEnabled = newDetectionEnabled
                motionDetectionEnabled = newMotionDetectionEnabled
                objectDetectionEnabled = newObjectDetectionEnabled
                AppPreferences.setDetectionEnabled(this, detectionEnabled)
                AppPreferences.setMotionDetectionEnabled(this, motionDetectionEnabled)
                AppPreferences.setObjectDetectionEnabled(this, objectDetectionEnabled)
                applyTimestampOverlay()
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

        // Update auth settings
        authEnabled = newAuthEnabled
        authUsername = newAuthUsername
        authPassword = newAuthPassword
        AppPreferences.setAuthEnabled(this, authEnabled)
        AppPreferences.setUsername(this, authUsername)
        AppPreferences.setPassword(this, authPassword)

        // Update overlay settings
        showTimestamp = newShowTimestamp
        showDate = newShowDate
        timestampPosition = newTimestampPosition
        timestampSize = newTimestampSize
        AppPreferences.setShowTimestamp(this, showTimestamp)
        AppPreferences.setShowDate(this, showDate)
        AppPreferences.setTimestampPosition(this, timestampPosition)
        AppPreferences.setTimestampSize(this, timestampSize)

        detectionEnabled = newDetectionEnabled
        motionDetectionEnabled = newMotionDetectionEnabled
        objectDetectionEnabled = newObjectDetectionEnabled
        AppPreferences.setDetectionEnabled(this, detectionEnabled)
        AppPreferences.setMotionDetectionEnabled(this, motionDetectionEnabled)
        AppPreferences.setObjectDetectionEnabled(this, objectDetectionEnabled)

        // Update flashlight & night mode settings
        val newFlashlightEnabled = intent?.getBooleanExtra("flashlight_enabled", AppPreferences.getFlashlightEnabled(this)) ?: false
        val newNightModeEnabled = intent?.getBooleanExtra("night_mode_enabled", AppPreferences.getNightModeEnabled(this)) ?: false
        flashlightEnabled = newFlashlightEnabled
        nightModeEnabled = newNightModeEnabled
        AppPreferences.setFlashlightEnabled(this, flashlightEnabled)
        AppPreferences.setNightModeEnabled(this, nightModeEnabled)

        if (showPreview != newShowPreview) {
             showPreview = newShowPreview
             AppPreferences.setShowPreview(this, showPreview)
             updateOverlaySize()
        }

        if (isSurfaceCreated) {
            startStream()
        }

        // Apply flashlight and night mode after stream starts
        Handler(Looper.getMainLooper()).postDelayed({
            applyFlashlight()
            updateNightModeSensor()
        }, 1000)

        return START_STICKY
    }

    private fun startStream() {
        if (!isSurfaceCreated || !openGlView.holder.surface.isValid) return
        
        try {
            if (!::rtspServerCamera.isInitialized) {
                rtspServerCamera = RtspServerCamera2(openGlView, this, 8554)
            }
            
            if (!rtspServerCamera.isStreaming) {
                // Resolve max resolution if needed
                if (videoWidth == 0 || videoHeight == 0) {
                    val maxRes = getMaxCameraResolution()
                    videoWidth = maxRes.first
                    videoHeight = maxRes.second
                    android.util.Log.d("CctvServerService", "Max resolution detected: ${videoWidth}x${videoHeight}")
                }

                // Dynamic Bitrate Calculation
                val bitrate = when {
                    videoWidth >= 1920 -> 6000 * 1024
                    videoWidth >= 1280 -> 4000 * 1024
                    else -> 2000 * 1024
                }

                // Audio is opt-in. Recording it forces the microphone foreground-service
                // type and the RECORD_AUDIO grant; a camera-only stream needs neither.
                if (audioEnabled && hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                    rtspServerCamera.prepareAudio(64 * 1024, 44100, true, false, false)
                } else {
                    rtspServerCamera.disableAudio()
                }

                // Check and set Codec
                val selectedCodec = when (videoCodec) {
                    "H265" -> VideoCodec.H265
                    "AV1" -> VideoCodec.AV1
                    // "VP9" was offered in both UIs but silently fell back to H.264.
                    // It is gone from the pickers; this branch only catches stale prefs.
                    else -> VideoCodec.H264
                }
                
                rtspServerCamera.setVideoCodec(selectedCodec)
                android.util.Log.d("CctvServerService", "Selected codec: $selectedCodec ($videoCodec)")

                // The "Force Software Codec" switch was previously persisted and even
                // restarted the stream, but was never applied to the encoder. Wire it to
                // the API that actually selects the codec implementation.
                val codecType = if (forceSoftware) {
                    CodecUtil.CodecType.SOFTWARE
                } else {
                    CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
                }
                rtspServerCamera.forceCodecType(codecType, codecType)
                android.util.Log.d("CctvServerService", "Codec type: $codecType")

                // Set authentication
                if (authEnabled && authUsername.isNotEmpty() && authPassword.isNotEmpty()) {
                    rtspServerCamera.getStreamClient().setAuthorization(authUsername, authPassword)
                    android.util.Log.d("CctvServerService", "RTSP auth enabled for user: $authUsername")
                } else {
                    rtspServerCamera.getStreamClient().setAuthorization("", "")
                    android.util.Log.d("CctvServerService", "RTSP auth disabled")
                }

                if (rtspServerCamera.prepareVideo(videoWidth, videoHeight, 30, bitrate, 0)) {
                    rtspServerCamera.startStream()
                    applyTimestampOverlay()
                } else {
                    android.util.Log.w("CctvServerService", "Codec $selectedCodec preparation failed, falling back to H264")
                    rtspServerCamera.setVideoCodec(VideoCodec.H264)
                    if (rtspServerCamera.prepareVideo(videoWidth, videoHeight, 30, bitrate, 0)) {
                         rtspServerCamera.startStream()
                         applyTimestampOverlay()
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

    private fun applyTimestampOverlay() {
        if (!showTimestamp && !showDate) {
            timestampHandler.removeCallbacks(timestampRunnable)
            textFilter = null
            return
        }

        try {
            val filter = TextObjectFilterRender()
            if (::rtspServerCamera.isInitialized) {
                rtspServerCamera.getGlInterface().setFilter(filter)
            }

            val fontSize = getOverlayFontSize()
            filter.setText(buildTimestampString(), fontSize, Color.WHITE)

            // Set position based on user preference (percentage-based)
            when (timestampPosition) {
                "Top Left" -> filter.setPosition(2f, 2f)
                "Top Right" -> filter.setPosition(65f, 2f)
                "Bottom Left" -> filter.setPosition(2f, 90f)
                "Bottom Right" -> filter.setPosition(65f, 90f)
            }

            // Scale based on size
            val scaleW = when (timestampSize) {
                "Small" -> 25f
                "Large" -> 45f
                else -> 35f
            }
            val scaleH = when (timestampSize) {
                "Small" -> 6f
                "Large" -> 14f
                else -> 10f
            }
            filter.setScale(scaleW, scaleH)

            textFilter = filter
            timestampHandler.removeCallbacks(timestampRunnable)
            timestampHandler.post(timestampRunnable)
            android.util.Log.d("CctvServerService", "Timestamp overlay applied at $timestampPosition, size=$timestampSize")
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to apply timestamp overlay", e)
        }
    }

    private fun updateTimestampText() {
        val filter = textFilter ?: return
        if (!showTimestamp && !showDate) return
        try {
            filter.setText(buildTimestampString(), getOverlayFontSize(), Color.WHITE)
        } catch (e: Exception) {
            // Ignore - filter may not be ready
        }
    }

    private fun getOverlayFontSize(): Float {
        return when (timestampSize) {
            "Small" -> 16f
            "Large" -> 30f
            else -> 22f  // Medium
        }
    }

    private fun buildTimestampString(): String {
        val now = Date()
        val parts = mutableListOf<String>()
        if (showDate) {
            parts.add(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now))
        }
        if (showTimestamp) {
            parts.add(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now))
        }
        return parts.joinToString(" ")
    }

    private fun getMaxCameraResolution(): Pair<Int, Int> {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Prefer the back camera rather than whichever id happens to be first --
            // on many devices id 0 is not the sensor actually being streamed, so the
            // "Max" resolution could be resolved from the wrong camera entirely.
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return Pair(1920, 1080)

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // Use SurfaceTexture sizes — these are video-encoder compatible
            val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                ?: return Pair(1920, 1080)
            // Cap at 4K (3840x2160) to avoid encoder failures
            val maxPixels = 3840 * 2160
            val validSizes = sizes.filter { it.width * it.height <= maxPixels }
            val maxSize = (if (validSizes.isNotEmpty()) validSizes else sizes.toList())
                .maxByOrNull { it.width * it.height } ?: return Pair(1920, 1080)
            android.util.Log.d("CctvServerService", "Camera max video size: ${maxSize.width}x${maxSize.height}")
            return Pair(maxSize.width, maxSize.height)
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to get max resolution", e)
            return Pair(1920, 1080)
        }
    }

    private fun applyFlashlight() {
        if (!::rtspServerCamera.isInitialized || !rtspServerCamera.isStreaming) return
        try {
            if (flashlightEnabled && !isLanternOn) {
                rtspServerCamera.enableLantern()
                isLanternOn = true
                android.util.Log.d("CctvServerService", "Flashlight ON")
            } else if (!flashlightEnabled && isLanternOn) {
                rtspServerCamera.disableLantern()
                isLanternOn = false
                android.util.Log.d("CctvServerService", "Flashlight OFF")
            }
        } catch (e: Exception) {
            android.util.Log.e("CctvServerService", "Failed to toggle flashlight", e)
        }
    }

    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (!nightModeEnabled) return
            val lux = event?.values?.get(0) ?: return
            val shouldEnableFlash = lux < 10f
            if (shouldEnableFlash != isLanternOn) {
                flashlightEnabled = shouldEnableFlash
                applyFlashlight()
                android.util.Log.d("CctvServerService", "Night mode: lux=$lux, flash=${if (shouldEnableFlash) "ON" else "OFF"}")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun updateNightModeSensor() {
        sensorManager?.unregisterListener(lightSensorListener)
        if (nightModeEnabled && lightSensor != null) {
            sensorManager?.registerListener(
                lightSensorListener,
                lightSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            android.util.Log.d("CctvServerService", "Night mode sensor registered")
        } else {
            android.util.Log.d("CctvServerService", "Night mode sensor unregistered")
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
        retentionHandler.removeCallbacks(retentionRunnable)
        timestampHandler.removeCallbacks(timestampRunnable)
        detectionExecutor.shutdownNow()
        sensorManager?.unregisterListener(lightSensorListener)
        
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

    /**
     * The foreground-service notification. A small icon is mandatory -- without one the
     * notification renders blank or is dropped outright, which for a camera that is
     * recording is both a usability and a transparency problem.
     */
    private fun buildNotification(): android.app.Notification {
        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = android.app.PendingIntent.getService(
            this,
            1,
            Intent(this, CctvServerService::class.java).setAction(ACTION_STOP_SERVER),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cctv)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
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