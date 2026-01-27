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
        val switchCodec = findViewById<Switch>(R.id.switch_codec)
        val textIpAddress = findViewById<TextView>(R.id.text_ip_address)

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

        switchServer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivityForResult(intent, 0)
                    switchServer.isChecked = false
                    Toast.makeText(this, "Please grant 'Display over other apps' permission", Toast.LENGTH_LONG).show()
                } else {
                    val intent = Intent(this, CctvServerService::class.java)
                    intent.putExtra("use_h265", switchCodec.isChecked)
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

        switchCodec.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(this, CctvServerService::class.java)
            intent.putExtra("use_h265", isChecked)
            startService(intent) // Restart the service with the new codec setting
        }
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