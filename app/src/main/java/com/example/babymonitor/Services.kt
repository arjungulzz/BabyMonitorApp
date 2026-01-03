package com.example.babymonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground Service to keep Baby Station running in background (eco mode).
 * Shows persistent notification and prevents Android from killing the process.
 */
class BabyMarkerService : Service() {
    
    companion object {
        private const val CHANNEL_ID = "baby_station_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.example.babymonitor.STOP_BABY_STATION"
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("BabyMarkerService", "onStartCommand called, action: ${intent?.action}")
        
        // Handle stop action from notification
        if (intent?.action == ACTION_STOP) {
            android.util.Log.d("BabyMarkerService", "Stop action received")
            stopBabyStation()
            return START_NOT_STICKY
        }
        
        try {
            android.util.Log.d("BabyMarkerService", "Creating notification...")
            
            // Create Stop PendingIntent
            val stopIntent = Intent(this, BabyMarkerService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Start foreground with notification
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Baby Station Active 👶")
                .setContentText("Monitoring is active")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(createContentIntent())
                .addAction(R.drawable.ic_close, "Stop Monitor", stopPendingIntent)
                .build()
            
            android.util.Log.d("BabyMarkerService", "Starting foreground service...")
            startForeground(NOTIFICATION_ID, notification)
            android.util.Log.d("BabyMarkerService", "Foreground service started successfully")
        } catch (e: Exception) {
            android.util.Log.e("BabyMarkerService", "Error starting foreground service", e)
        }
        
        return START_STICKY // Restart if killed
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.util.Log.d("BabyMarkerService", "Creating notification channel...")
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Baby Station Active",
                NotificationManager.IMPORTANCE_HIGH // Changed to HIGH for visibility
            ).apply {
                description = "Shows when Baby Station is monitoring"
                setShowBadge(true)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            android.util.Log.d("BabyMarkerService", "Notification channel created")
        }
    }
    
    private fun createContentIntent(): PendingIntent {
        val intent = Intent(this, BabyStationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    
    private fun createStopAction(): NotificationCompat.Action {
        val stopIntent = Intent(this, BabyMarkerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stop,
            "Stop",
            stopPendingIntent
        ).build()
    }
    
    private fun stopBabyStation() {
        // Broadcast to BabyStationActivity to stop monitoring
        val intent = Intent("com.example.babymonitor.ACTION_STOP_MONITORING")
        intent.setPackage(packageName)
        sendBroadcast(intent)
        
        stopForeground(true)
        stopSelf()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
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
