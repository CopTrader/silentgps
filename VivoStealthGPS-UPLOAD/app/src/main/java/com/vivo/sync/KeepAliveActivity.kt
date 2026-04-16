package com.vivo.sync
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
class KeepAliveActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1000) }
}