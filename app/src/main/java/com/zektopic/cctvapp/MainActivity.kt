package com.zektopic.cctvapp

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Switch
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.os.Build

class MainActivity : AppCompatActivity() {

    private val CAMERA_PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val switchServer = findViewById<Switch>(R.id.switch_server)
        val spinnerCodec = findViewById<android.widget.Spinner>(R.id.spinner_codec)
        val btnSwitchCamera = findViewById<Button>(R.id.btn_switch_camera)
        val switchPreview = findViewById<Switch>(R.id.switch_preview)
        val switchForceSoftware = findViewById<Switch>(R.id.switch_force_software)
        val spinnerResolution = findViewById<android.widget.Spinner>(R.id.spinner_resolution)
        val textIpAddress = findViewById<TextView>(R.id.text_ip_address)

        // Setup Resolution Spinner
        val resolutions = arrayOf("640x480", "1280x720", "1920x1080")
        val resAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutions)
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerResolution.adapter = resAdapter
        spinnerResolution.setSelection(0) // Default 480p

        // Setup Codec Spinner
        val codecs = arrayOf("H264", "H265", "VP9", "AV1")
        val codecAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, codecs)
        codecAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCodec.adapter = codecAdapter
        spinnerCodec.setSelection(0) // Default H264

        // Request camera and audio permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), CAMERA_PERMISSION_REQUEST_CODE)
        }

        // Get and display the device's IP address
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        val formattedIpAddress = String.format("%d.%d.%d.%d",
            (ipAddress and 0xff),
            (ipAddress shr 8 and 0xff),
            (ipAddress shr 16 and 0xff),
            (ipAddress shr 24 and 0xff))
        textIpAddress.text = formattedIpAddress

        switchServer.isChecked = isServiceRunning(CctvServerService::class.java)

        fun getSelectedResolution(): Pair<Int, Int> {
            val selectedItem = spinnerResolution.selectedItem as String
            val parts = selectedItem.split("x")
            return Pair(parts[0].toInt(), parts[1].toInt())
        }

        switchServer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivityForResult(intent, 0)
                    switchServer.isChecked = false
                    Toast.makeText(this, "Please grant 'Display over other apps' permission", Toast.LENGTH_LONG).show()
                } else {
                    val intent = Intent(this, CctvServerService::class.java)
                    intent.putExtra("video_codec", spinnerCodec.selectedItem as String)
                    intent.putExtra("force_software", switchForceSoftware.isChecked)
                    intent.putExtra("show_preview", switchPreview.isChecked)
                    val (w, h) = getSelectedResolution()
                    intent.putExtra("width", w)
                    intent.putExtra("height", h)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            } else {
                stopService(Intent(this, CctvServerService::class.java))
            }
        }

        spinnerCodec.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                 if (switchServer.isChecked) {
                    val intent = Intent(this@MainActivity, CctvServerService::class.java)
                    intent.putExtra("video_codec", spinnerCodec.selectedItem as String)
                    intent.putExtra("force_software", switchForceSoftware.isChecked)
                    intent.putExtra("show_preview", switchPreview.isChecked)
                    val (w, h) = getSelectedResolution()
                    intent.putExtra("width", w)
                    intent.putExtra("height", h)
                    startService(intent) // Restart the service with the new codec setting
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        btnSwitchCamera.setOnClickListener {
             if (switchServer.isChecked) {
                val intent = Intent(this, CctvServerService::class.java)
                intent.action = "ACTION_SWITCH_CAMERA"
                startService(intent)
             }
        }

        switchPreview.setOnCheckedChangeListener { _, isChecked ->
             if (switchServer.isChecked) {
                val intent = Intent(this, CctvServerService::class.java)
                intent.action = "ACTION_TOGGLE_PREVIEW"
                intent.putExtra("show_preview", isChecked)
                startService(intent)
             }
        }

        switchForceSoftware.setOnCheckedChangeListener { _, isChecked ->
             if (switchServer.isChecked) {
                 val intent = Intent(this@MainActivity, CctvServerService::class.java)
                 intent.putExtra("video_codec", spinnerCodec.selectedItem as String)
                 intent.putExtra("force_software", isChecked)
                 intent.putExtra("show_preview", switchPreview.isChecked)
                 val (w, h) = getSelectedResolution()
                 intent.putExtra("width", w)
                 intent.putExtra("height", h)
                 startService(intent)
             }
        }

        spinnerResolution.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                 if (switchServer.isChecked) {
                    val intent = Intent(this@MainActivity, CctvServerService::class.java)
                    intent.putExtra("video_codec", spinnerCodec.selectedItem as String)
                    intent.putExtra("force_software", switchForceSoftware.isChecked)
                    intent.putExtra("show_preview", switchPreview.isChecked)
                    val (w, h) = getSelectedResolution()
                    intent.putExtra("width", w)
                    intent.putExtra("height", h)
                    startService(intent)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        // Auto-start if permissions are already granted
        if (!switchServer.isChecked && allPermissionsGranted() && Settings.canDrawOverlays(this)) {
             switchServer.isChecked = true
        }
    }

    private fun allPermissionsGranted() = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}