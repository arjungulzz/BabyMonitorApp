package com.example.babymonitor

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Empty Service used solely as a runtime marker to detect if BabyStation is active.
 * Using a Service ensures the Android System tracks the lifecycle for us.
 */
class BabyMarkerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY // If killed, don't restart (we want to reflect actual activity state)
    }
}

/**
 * Empty Service used solely as a runtime marker to detect if ParentStation is active.
 */
class ParentMarkerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }
}
