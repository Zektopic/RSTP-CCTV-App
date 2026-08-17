package com.zektopic.cctvapp

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zektopic.cctvapp.databinding.ActivityMainBinding
import java.net.Inet4Address

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * CAMERA is the only hard requirement. RECORD_AUDIO is requested alongside it so the
     * optional audio toggle works without a second prompt, and POST_NOTIFICATIONS is
     * needed from API 33 for the foreground-service notification to appear at all.
     */
    private val permissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    /** Only these block the server from running. */
    private val requiredPermissions = arrayOf(Manifest.permission.CAMERA)

    private val permissionRequestCode = 100

    private val resolutions = arrayOf("640x480", "1280x720", "1920x1080", "Max")
    private val codecs = arrayOf("H264", "H265", "AV1")
    private val overlayPositions = arrayOf("Top Left", "Top Right", "Bottom Left", "Bottom Right")
    private val overlaySizes = arrayOf("Small", "Medium", "Large")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        requestPermissionsIfNeeded()
        updateNetworkInfo()
        showGeneratedPasswordIfAny()
        autoStartServerIfNeeded()
    }

    private fun setupViews() {
        setupSpinners()
        loadSavedSettings()
        setupListeners()
        updateServerStatus(isServiceRunning(CctvServerService::class.java))
    }

    private fun setupSpinners() {
        (binding.spinnerResolution as? AutoCompleteTextView)?.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutions)
        )

        (binding.spinnerCodec as? AutoCompleteTextView)?.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, codecs)
        )

        (binding.spinnerOverlayPosition as? AutoCompleteTextView)?.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, overlayPositions)
        )

        (binding.spinnerOverlaySize as? AutoCompleteTextView)?.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, overlaySizes)
        )
    }

    private fun loadSavedSettings() {
        // Load saved codec
        val savedCodec = AppPreferences.getVideoCodec(this)
        (binding.spinnerCodec as? AutoCompleteTextView)?.setText(
            if (savedCodec in codecs) savedCodec else codecs.first(), false
        )

        // Load saved resolution
        val savedWidth = AppPreferences.getVideoWidth(this)
        val savedHeight = AppPreferences.getVideoHeight(this)
        val savedResolution = if (savedWidth == 0 && savedHeight == 0) "Max" else "${savedWidth}x${savedHeight}"
        (binding.spinnerResolution as? AutoCompleteTextView)?.setText(
            if (savedResolution in resolutions) savedResolution else resolutions.first(), false
        )

        // Load saved toggles
        binding.switchForceSoftware.isChecked = AppPreferences.getForceSoftware(this)
        binding.switchPreview.isChecked = AppPreferences.getShowPreview(this)

        // Load saved auth settings
        val authEnabled = AppPreferences.getAuthEnabled(this)
        binding.switchAuth.isChecked = authEnabled
        binding.editUsername.setText(AppPreferences.getUsername(this))
        binding.editPassword.setText(AppPreferences.getPassword(this))
        setAuthFieldsEnabled(authEnabled)

        // Load saved overlay settings
        binding.switchTimestamp.isChecked = AppPreferences.getShowTimestamp(this)
        binding.switchDate.isChecked = AppPreferences.getShowDate(this)
        val savedPosition = AppPreferences.getTimestampPosition(this)
        (binding.spinnerOverlayPosition as? AutoCompleteTextView)?.setText(
            if (savedPosition in overlayPositions) savedPosition else overlayPositions.first(), false
        )
        val savedSize = AppPreferences.getTimestampSize(this)
        (binding.spinnerOverlaySize as? AutoCompleteTextView)?.setText(
            if (savedSize in overlaySizes) savedSize else overlaySizes[1], false
        )

        // Load saved flashlight & night mode settings
        binding.switchFlashlight.isChecked = AppPreferences.getFlashlightEnabled(this)
        binding.switchNightMode.isChecked = AppPreferences.getNightModeEnabled(this)

        // Load detection settings
        binding.switchDetectionEnabled.isChecked = AppPreferences.getDetectionEnabled(this)
        binding.switchMotionDetection.isChecked = AppPreferences.getMotionDetectionEnabled(this)
        binding.switchObjectDetection.isChecked = AppPreferences.getObjectDetectionEnabled(this)
        binding.sliderMotionSensitivity.value = AppPreferences.getMotionSensitivity(this).toFloat()
        binding.sliderDetectionCooldown.value =
            AppPreferences.getDetectionCooldownSeconds(this).toFloat().coerceIn(5f, 120f)

        // Load security & startup settings
        binding.switchWebAuth.isChecked = AppPreferences.getWebAuthEnabled(this)
        binding.switchAudio.isChecked = AppPreferences.getAudioEnabled(this)
        binding.switchStartOnBoot.isChecked = AppPreferences.getStartOnBoot(this)
        binding.switchAutoStart.isChecked = AppPreferences.getAutoStartOnLaunch(this)
    }

    private fun setAuthFieldsEnabled(enabled: Boolean) {
        binding.layoutUsername.isEnabled = enabled
        binding.editUsername.isEnabled = enabled
        binding.layoutPassword.isEnabled = enabled
        binding.editPassword.isEnabled = enabled
        binding.btnGeneratePassword.isEnabled = enabled
    }

    // startServer() decides the resulting state itself, because it can refuse (missing
    // overlay or camera permission). The listener must not assert `true` afterwards or
    // it overwrites that refusal and leaves the switch on with no service behind it.
    private val serverSwitchListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startServer()
            } else {
                stopService(Intent(this, CctvServerService::class.java))
                updateServerStatus(false)
            }
        }

    private fun setupListeners() {
        binding.switchServer.setOnCheckedChangeListener(serverSwitchListener)

        binding.btnSwitchCamera.setOnClickListener {
            if (binding.switchServer.isChecked) {
                sendServiceAction("ACTION_SWITCH_CAMERA")
            }
        }

        binding.switchPreview.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setShowPreview(this, isChecked)
            if (binding.switchServer.isChecked) {
                val intent = Intent(this, CctvServerService::class.java).apply {
                    action = "ACTION_TOGGLE_PREVIEW"
                    putExtra("show_preview", isChecked)
                }
                startService(intent)
            }
        }

        binding.switchForceSoftware.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setForceSoftware(this, isChecked)
            restartServer()
        }

        (binding.spinnerResolution as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            val (width, height) = parseResolution(resolutions[position])
            AppPreferences.setResolution(this, width, height)
            restartServer()
        }

        (binding.spinnerCodec as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            AppPreferences.setVideoCodec(this, codecs[position])
            restartServer()
        }

        // Auth listeners
        binding.switchAuth.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setAuthEnabled(this, isChecked)
            setAuthFieldsEnabled(isChecked)
            updateNetworkInfo()
            restartServer()
        }

        // Persist credentials on focus loss, but only restart the stream when they
        // actually changed -- tabbing through the fields used to tear the stream down
        // and bring it back up each time.
        binding.editUsername.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyCredentialsIfChanged()
        }

        binding.editPassword.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyCredentialsIfChanged()
        }

        binding.btnGeneratePassword.setOnClickListener {
            binding.editPassword.setText(WebAuth.generatePassword(16))
            applyCredentialsIfChanged()
            Toast.makeText(this, R.string.password_generated, Toast.LENGTH_SHORT).show()
        }

        // Overlay listeners
        binding.switchTimestamp.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setShowTimestamp(this, isChecked)
            restartServer()
        }

        binding.switchDate.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setShowDate(this, isChecked)
            restartServer()
        }

        (binding.spinnerOverlayPosition as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            AppPreferences.setTimestampPosition(this, overlayPositions[position])
            restartServer()
        }

        (binding.spinnerOverlaySize as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            AppPreferences.setTimestampSize(this, overlaySizes[position])
            restartServer()
        }

        // Copy buttons
        binding.btnCopyRtsp.setOnClickListener {
            copyToClipboard("RTSP URL", binding.textRtspUrl.text.toString())
        }

        binding.btnCopyWeb.setOnClickListener {
            copyToClipboard("Web URL", binding.textWebUrl.text.toString())
        }

        // Flashlight & Night Mode listeners
        binding.switchFlashlight.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setFlashlightEnabled(this, isChecked)
            if (binding.switchServer.isChecked) {
                val intent = Intent(this, CctvServerService::class.java).apply {
                    action = "ACTION_TOGGLE_FLASHLIGHT"
                    putExtra("flashlight_enabled", isChecked)
                }
                startService(intent)
            }
        }

        binding.switchNightMode.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setNightModeEnabled(this, isChecked)
            if (binding.switchServer.isChecked) {
                val intent = Intent(this, CctvServerService::class.java).apply {
                    action = "ACTION_TOGGLE_NIGHT_MODE"
                    putExtra("night_mode_enabled", isChecked)
                }
                startService(intent)
            }
        }

        binding.switchDetectionEnabled.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setDetectionEnabled(this, isChecked)
            restartServer()
        }

        binding.switchMotionDetection.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setMotionDetectionEnabled(this, isChecked)
            restartServer()
        }

        binding.switchObjectDetection.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setObjectDetectionEnabled(this, isChecked)
            restartServer()
        }

        binding.sliderMotionSensitivity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) AppPreferences.setMotionSensitivity(this, value.toInt())
        }

        binding.sliderDetectionCooldown.addOnChangeListener { _, value, fromUser ->
            if (fromUser) AppPreferences.setDetectionCooldownSeconds(this, value.toInt())
        }

        binding.switchWebAuth.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setWebAuthEnabled(this, isChecked)
            if (isChecked) showGeneratedPasswordIfAny()
            sendSettingToService("web_auth_enabled", isChecked.toString())
        }

        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setAudioEnabled(this, isChecked)
            restartServer()
        }

        binding.switchStartOnBoot.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setStartOnBoot(this, isChecked)
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setAutoStartOnLaunch(this, isChecked)
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_events) {
                startActivity(Intent(this, EventsActivity::class.java))
                true
            } else {
                false
            }
        }
    }

    /**
     * Reflects the real service state in the UI.
     *
     * The listener is detached first: assigning `isChecked` fires the change listener,
     * which starts the service -- so simply *displaying* the current state used to
     * start the server, including during initial setup.
     */
    private fun updateServerStatus(isRunning: Boolean) {
        binding.switchServer.setOnCheckedChangeListener(null)
        binding.switchServer.isChecked = isRunning
        binding.switchServer.setOnCheckedChangeListener(serverSwitchListener)

        binding.statusDot.setBackgroundResource(
            if (isRunning) R.drawable.status_dot_green else R.drawable.status_dot_red
        )
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show()
    }

    private fun startServer() {
        if (!allPermissionsGranted()) {
            requestPermissionsIfNeeded()
            updateServerStatus(false)
            Toast.makeText(this, R.string.camera_permission_toast, Toast.LENGTH_LONG).show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            updateServerStatus(false)
            Toast.makeText(this, R.string.overlay_permission_toast, Toast.LENGTH_LONG).show()
            return
        }

        restartServer()
        updateServerStatus(true)
    }

    private fun restartServer() {
        if (!binding.switchServer.isChecked) return

        val (width, height) = getSelectedResolution()
        val intent = Intent(this, CctvServerService::class.java).apply {
            putExtra("video_codec", binding.spinnerCodec.text.toString())
            putExtra("force_software", binding.switchForceSoftware.isChecked)
            putExtra("show_preview", binding.switchPreview.isChecked)
            putExtra("width", width)
            putExtra("height", height)
            putExtra("auth_enabled", binding.switchAuth.isChecked)
            putExtra("auth_username", binding.editUsername.text.toString())
            putExtra("auth_password", binding.editPassword.text.toString())
            putExtra("show_timestamp", binding.switchTimestamp.isChecked)
            putExtra("show_date", binding.switchDate.isChecked)
            putExtra("timestamp_position", binding.spinnerOverlayPosition.text.toString())
            putExtra("timestamp_size", binding.spinnerOverlaySize.text.toString())
            putExtra("flashlight_enabled", binding.switchFlashlight.isChecked)
            putExtra("night_mode_enabled", binding.switchNightMode.isChecked)
            putExtra("detection_enabled", binding.switchDetectionEnabled.isChecked)
            putExtra("motion_detection_enabled", binding.switchMotionDetection.isChecked)
            putExtra("object_detection_enabled", binding.switchObjectDetection.isChecked)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** Pushes a single setting to a running service without restarting the stream. */
    private fun sendSettingToService(key: String, value: String) {
        if (!binding.switchServer.isChecked) return
        val intent = Intent(this, CctvServerService::class.java).apply {
            action = "ACTION_SET_SETTING"
            putExtra("setting_key", key)
            putExtra("setting_value", value)
        }
        startService(intent)
    }

    private fun sendServiceAction(action: String) {
        Intent(this, CctvServerService::class.java).also { intent ->
            intent.action = action
            startService(intent)
        }
    }

    private fun getSelectedResolution(): Pair<Int, Int> =
        parseResolution(binding.spinnerResolution.text.toString())

    companion object {
        /** Sentinel meaning "ask the camera for its maximum"; resolved in the service. */
        val MAX_RESOLUTION = Pair(0, 0)
        private val DEFAULT_RESOLUTION = Pair(640, 480)

        /**
         * Parses a "WIDTHxHEIGHT" label, or "Max".
         *
         * The picker is an AutoCompleteTextView, so its contents are whatever the user
         * typed -- `parts[0].toInt()` on that threw NumberFormatException and took the
         * app down. Anything unparseable now falls back to the default resolution.
         */
        fun parseResolution(value: String): Pair<Int, Int> {
            val trimmed = value.trim()
            if (trimmed.equals("Max", ignoreCase = true)) return MAX_RESOLUTION

            val parts = trimmed.split("x", "X")
            if (parts.size != 2) return DEFAULT_RESOLUTION

            val width = parts[0].trim().toIntOrNull() ?: return DEFAULT_RESOLUTION
            val height = parts[1].trim().toIntOrNull() ?: return DEFAULT_RESOLUTION
            if (width <= 0 || height <= 0) return DEFAULT_RESOLUTION

            return Pair(width, height)
        }
    }

    private fun updateNetworkInfo() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        val ipv4Address = linkProperties?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress

        val ip = ipv4Address ?: getString(R.string.ip_not_available)
        binding.textIpAddress.text = ip

        // Set RTSP and Web URLs
        if (ipv4Address != null) {
            val authEnabled = AppPreferences.getAuthEnabled(this)
            val username = AppPreferences.getUsername(this)
            val password = AppPreferences.getPassword(this)
            if (authEnabled && username.isNotEmpty() && password.isNotEmpty()) {
                binding.textRtspUrl.text = "rtsp://$username:$password@$ip:8554/stream"
            } else {
                binding.textRtspUrl.text = "rtsp://$ip:8554/stream"
            }
            binding.textWebUrl.text = "http://$ip:8080"
        } else {
            binding.textRtspUrl.text = getString(R.string.ip_not_available)
            binding.textWebUrl.text = getString(R.string.ip_not_available)
        }
    }

    /** Saves the credential fields, refreshing the URLs and the stream only if they changed. */
    private fun applyCredentialsIfChanged() {
        val username = binding.editUsername.text.toString()
        val password = binding.editPassword.text.toString()

        val changed = username != AppPreferences.getUsername(this) ||
            password != AppPreferences.getPassword(this)
        if (!changed) return

        AppPreferences.setUsername(this, username)
        AppPreferences.setPassword(this, password)
        updateNetworkInfo()
        restartServer()
    }

    /**
     * Shows the password generated on first run so the user is not locked out of the
     * now-authenticated dashboard.
     */
    private fun showGeneratedPasswordIfAny() {
        val generated = AppPreferences.seedCredentialsIfMissing(this) ?: return

        binding.editUsername.setText(AppPreferences.getUsername(this))
        binding.editPassword.setText(generated)

        // Only claim the dashboard is protected when it actually is. The password is
        // seeded regardless of the toggle, so with it off the old wording told the user
        // they were covered while the dashboard stayed reachable by anyone on the network.
        val message = if (AppPreferences.getWebAuthEnabled(this)) {
            getString(R.string.generated_password_message, generated)
        } else {
            getString(R.string.generated_password_message_unprotected, generated)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.generated_password_title)
            .setMessage(message)
            .setPositiveButton(R.string.generated_password_ok, null)
            .show()
    }

    private fun requestPermissionsIfNeeded() {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, permissions, permissionRequestCode)
        }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts the server on launch only when the user has explicitly asked for it.
     *
     * This used to fire unconditionally, so merely opening the app switched the camera
     * on and published a stream to the network without any confirmation.
     */
    private fun autoStartServerIfNeeded() {
        if (!AppPreferences.getAutoStartOnLaunch(this)) return
        if (binding.switchServer.isChecked) return
        if (!allPermissionsGranted() || !Settings.canDrawOverlays(this)) return
        binding.switchServer.isChecked = true
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
