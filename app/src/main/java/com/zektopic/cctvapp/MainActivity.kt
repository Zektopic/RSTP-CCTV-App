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
    private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val permissionRequestCode = 100

    private val resolutions = arrayOf("640x480", "1280x720", "1920x1080")
    private val codecs = arrayOf("H264", "H265", "VP9", "AV1")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        requestPermissionsIfNeeded()
        updateNetworkInfo()
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
        val savedResolution = "${savedWidth}x${savedHeight}"
        (binding.spinnerResolution as? AutoCompleteTextView)?.setText(
            if (savedResolution in resolutions) savedResolution else resolutions.first(), false
        )

        // Load saved toggles
        binding.switchForceSoftware.isChecked = AppPreferences.getForceSoftware(this)
        binding.switchPreview.isChecked = AppPreferences.getShowPreview(this)
    }

    private fun setupListeners() {
        binding.switchServer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startServer()
            } else {
                stopService(Intent(this, CctvServerService::class.java))
            }
            updateServerStatus(isChecked)
        }

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
            val selected = resolutions[position]
            val parts = selected.split("x")
            AppPreferences.setResolution(this, parts[0].toInt(), parts[1].toInt())
            restartServer()
        }

        (binding.spinnerCodec as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            AppPreferences.setVideoCodec(this, codecs[position])
            restartServer()
        }

        // Copy buttons
        binding.btnCopyRtsp.setOnClickListener {
            copyToClipboard("RTSP URL", binding.textRtspUrl.text.toString())
        }

        binding.btnCopyWeb.setOnClickListener {
            copyToClipboard("Web URL", binding.textWebUrl.text.toString())
        }
    }

    private fun updateServerStatus(isRunning: Boolean) {
        binding.switchServer.isChecked = isRunning
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
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, 0)
            binding.switchServer.isChecked = false
            updateServerStatus(false)
            Toast.makeText(this, R.string.overlay_permission_toast, Toast.LENGTH_LONG).show()
            return
        }
        restartServer()
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
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun sendServiceAction(action: String) {
        Intent(this, CctvServerService::class.java).also { intent ->
            intent.action = action
            startService(intent)
        }
    }

    private fun getSelectedResolution(): Pair<Int, Int> {
        val selected = binding.spinnerResolution.text.toString()
        val parts = selected.split("x")
        return Pair(parts[0].toInt(), parts[1].toInt())
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
            binding.textRtspUrl.text = "rtsp://$ip:8554/stream"
            binding.textWebUrl.text = "http://$ip:8080"
        } else {
            binding.textRtspUrl.text = getString(R.string.ip_not_available)
            binding.textWebUrl.text = getString(R.string.ip_not_available)
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, permissions, permissionRequestCode)
        }
    }

    private fun allPermissionsGranted() = permissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun autoStartServerIfNeeded() {
        if (!binding.switchServer.isChecked && allPermissionsGranted() && Settings.canDrawOverlays(this)) {
            binding.switchServer.isChecked = true
        }
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
