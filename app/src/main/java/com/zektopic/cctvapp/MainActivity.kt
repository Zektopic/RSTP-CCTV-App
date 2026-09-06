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

    /** Counts taps on the version row; see [TapUnlock] for why it is time-windowed. */
    private val advancedUnlock = TapUnlock()

    private val resolutions = arrayOf("640x480", "1280x720", "1920x1080", "Max")
    private val codecs = arrayOf("H264", "H265", "AV1")
    private val overlayPositions = arrayOf("Top Left", "Top Right", "Bottom Left", "Bottom Right")
    private val overlaySizes = arrayOf("Small", "Medium", "Large")

    /** Encoder implementations, in the order the picker shows them. */
    private val encoderImplementations = EncoderImplementation.entries.toList()

    /**
     * Bitrate choices for the advanced picker, in kbit/s.
     *
     * [AppPreferences.BITRATE_AUTO] leads, so the default sits at the top of the list
     * and a user who opens the dropdown out of curiosity sees where they already are.
     */
    private val bitrateChoicesKbps =
        intArrayOf(AppPreferences.BITRATE_AUTO, 1000, 2000, 4000, 6000, 8000, 12000, 20000)

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
        setupAdvancedSection()
        updateServerStatus(isServiceRunning(CctvServerService::class.java))
    }

    /**
     * Wires the version row, the reveal gesture and the Advanced card itself.
     *
     * The card is `gone` in the layout, so an install that has never performed the
     * gesture renders exactly as before.
     */
    private fun setupAdvancedSection() {
        binding.textVersion.text = getString(R.string.version_row, versionName())
        loadAdvancedSettings()
        setAdvancedVisible(AppPreferences.getAdvancedUnlocked(this))

        binding.textVersion.setOnClickListener {
            if (AppPreferences.getAdvancedUnlocked(this)) {
                toast(getString(R.string.advanced_already_unlocked))
                return@setOnClickListener
            }

            val result = advancedUnlock.tap(System.currentTimeMillis())
            when {
                result.unlocked -> {
                    AppPreferences.setAdvancedUnlocked(this, true)
                    setAdvancedVisible(true)
                    toast(getString(R.string.advanced_unlocked))
                }
                result.showCountdown -> toast(
                    resources.getQuantityString(
                        R.plurals.advanced_countdown,
                        result.remaining,
                        result.remaining
                    )
                )
            }
        }

        binding.btnHideAdvanced.setOnClickListener {
            AppPreferences.setAdvancedUnlocked(this, false)
            advancedUnlock.reset()
            setAdvancedVisible(false)
            toast(getString(R.string.advanced_hidden))
        }

        binding.btnResetAdvanced.setOnClickListener {
            AppPreferences.resetAdvancedSettings(this)
            // Re-read rather than assuming the defaults here, so this stays correct as
            // further advanced settings are added to AppPreferences.ADVANCED_KEYS.
            loadAdvancedSettings()
            toast(getString(R.string.advanced_reset_done))
            restartServer()
        }
    }

    /**
     * The installed versionName.
     *
     * Read from the PackageManager rather than BuildConfig, which this module does not
     * generate (`buildFeatures` enables viewBinding only), and because CI overrides
     * versionName from the release tag -- so the package is the one source that is right
     * for both local and published builds.
     */
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        "?"
    }

    /**
     * Reflects the persisted advanced values back into their controls.
     *
     * Listeners are detached around every write. "Reset advanced settings" calls this,
     * and setting a control while its listener is attached would fire another save and
     * another stream restart for each one.
     */
    private fun loadAdvancedSettings() {
        (binding.spinnerEncoderImpl as? AutoCompleteTextView)?.setText(
            getString(encoderImplementationLabel(AppPreferences.getEncoderImplementation(this))),
            false
        )
        updateCodecSupportLabel()

        val bitrate = AppPreferences.getBitrateKbps(this)
        (binding.spinnerBitrate as? AutoCompleteTextView)?.setText(bitrateLabel(bitrate), false)
        updateBitrateSummary(bitrate)

        val fps = AppPreferences.getVideoFps(this)
        (binding.spinnerFps as? AutoCompleteTextView)?.setText(
            getString(R.string.encoder_fps_value, fps), false
        )

        val keyframe = AppPreferences.getKeyframeIntervalSeconds(this)
        binding.sliderKeyframe.clearOnChangeListeners()
        binding.sliderKeyframe.value = keyframe.toFloat()
        binding.sliderKeyframe.addOnChangeListener(keyframeListener)
        binding.labelKeyframe.text = getString(R.string.encoder_keyframe_label, keyframe)
    }

    /**
     * Tells the user what this device can actually encode.
     *
     * The codec picker offers H.264, H.265 and AV1 everywhere, but most hardware has no
     * AV1 encoder at all -- and choosing it there just made the stream fall back to
     * H.264, with nothing on screen to explain why the setting appeared not to stick.
     *
     * The probe is a MediaCodecList enumeration that takes single-digit milliseconds and
     * runs only when the section is populated, so it stays on the main thread.
     */
    private fun updateCodecSupportLabel() {
        val support = CodecCapabilities.probe()
        val described = support.entries.joinToString(", ") { (codec, codecSupport) ->
            val how = when {
                codecSupport.hardware && codecSupport.software -> R.string.codec_support_both
                codecSupport.hardware -> R.string.codec_support_hardware
                codecSupport.software -> R.string.codec_support_software
                else -> R.string.codec_support_none
            }
            getString(R.string.codec_support_entry, codec, getString(how))
        }
        binding.labelCodecSupport.text = getString(
            R.string.codec_support_label,
            described.ifEmpty { getString(R.string.codec_support_unknown) }
        )
    }

    /**
     * Explains what the bitrate setting currently means.
     *
     * On Auto the interesting number is what the ladder resolved to, so it is computed
     * here from the same [EncoderProfile] the service uses rather than left as the word
     * "Auto", which tells the user nothing about the bandwidth they are about to use.
     */
    private fun updateBitrateSummary(kbps: Int) {
        binding.labelBitrateSummary.text = if (kbps == AppPreferences.BITRATE_AUTO) {
            var width = AppPreferences.getVideoWidth(this)
            var height = AppPreferences.getVideoHeight(this)
            // "Max" is stored as 0x0 and only resolved against the camera inside the
            // service. Show the 1080p figure rather than the degenerate zero case.
            if (width == 0 || height == 0) {
                width = 1920
                height = 1080
            }
            getString(
                R.string.encoder_bitrate_auto_summary,
                EncoderProfile.autoBitrateKbps(
                    width = width,
                    height = height,
                    fps = AppPreferences.getVideoFps(this),
                    codec = AppPreferences.getVideoCodec(this)
                )
            )
        } else {
            getString(R.string.encoder_bitrate_manual_summary, kbps)
        }
    }

    private val keyframeListener =
        com.google.android.material.slider.Slider.OnChangeListener { _, value, fromUser ->
            val seconds = value.toInt()
            binding.labelKeyframe.text = getString(R.string.encoder_keyframe_label, seconds)
            if (!fromUser) return@OnChangeListener
            AppPreferences.setKeyframeIntervalSeconds(this, seconds)
            restartServer()
        }

    private fun setAdvancedVisible(visible: Boolean) {
        binding.cardAdvanced.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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

        (binding.spinnerEncoderImpl as? AutoCompleteTextView)?.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                encoderImplementations.map { getString(encoderImplementationLabel(it)) }
            )
        )

        (binding.spinnerBitrate as? AutoCompleteTextView)?.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                bitrateChoicesKbps.map { bitrateLabel(it) }
            )
        )

        (binding.spinnerFps as? AutoCompleteTextView)?.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                EncoderProfile.FPS_CHOICES.map { getString(R.string.encoder_fps_value, it) }
            )
        )
    }

    private fun encoderImplementationLabel(implementation: EncoderImplementation): Int =
        when (implementation) {
            EncoderImplementation.AUTO -> R.string.encoder_impl_auto
            EncoderImplementation.HARDWARE -> R.string.encoder_impl_hardware
            EncoderImplementation.SOFTWARE -> R.string.encoder_impl_software
            EncoderImplementation.CBR_PRIORITY -> R.string.encoder_impl_cbr
        }

    private fun bitrateLabel(kbps: Int): String =
        if (kbps == AppPreferences.BITRATE_AUTO) {
            getString(R.string.encoder_bitrate_auto)
        } else {
            getString(R.string.encoder_bitrate_manual_value, kbps)
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

        (binding.spinnerEncoderImpl as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            AppPreferences.setEncoderImplementation(this, encoderImplementations[position])
            // Which implementations are usable depends on the codec, so the support
            // line under the picker can change meaning with this choice.
            updateCodecSupportLabel()
            restartServer()
        }

        (binding.spinnerBitrate as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            val kbps = bitrateChoicesKbps[position]
            AppPreferences.setBitrateKbps(this, kbps)
            updateBitrateSummary(kbps)
            restartServer()
        }

        (binding.spinnerFps as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            AppPreferences.setVideoFps(this, EncoderProfile.FPS_CHOICES[position])
            // The auto bitrate scales with frame rate, so the summary above it is now stale.
            updateBitrateSummary(AppPreferences.getBitrateKbps(this))
            restartServer()
        }

        binding.sliderKeyframe.addOnChangeListener(keyframeListener)

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
