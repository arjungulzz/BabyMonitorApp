package apadev232228.babymonitor

import android.app.Application

class BabyMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install Global Crash Handler to auto-restart on crash
        GlobalCrashHandler.install(this)
    }
}
