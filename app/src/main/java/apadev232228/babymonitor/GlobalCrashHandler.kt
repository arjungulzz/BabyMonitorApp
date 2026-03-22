package apadev232228.babymonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class GlobalCrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashReport(throwable)
            
            // Auto-Restart logic
            // We want to restart the Baby Station if possible, or just the Launcher
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent)
            
            Log.e("GlobalCrashHandler", "App crashed, scheduling restart...", throwable)
            
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Kill generic crash dialog to allow restart
            defaultHandler?.uncaughtException(thread, throwable) ?: run {
                 Process.killProcess(Process.myPid())
                 exitProcess(10)
            }
        }
    }

    private fun saveCrashReport(throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()
        
        try {
            val file = File(context.getExternalFilesDir(null), "crash_log.txt")
            val writer = FileWriter(file, true)
            writer.append("\n\n--- Crash Report: ${java.util.Date()} ---\n")
            writer.append(stackTrace)
            writer.flush()
            writer.close()
        } catch (ioe: IOException) {
            ioe.printStackTrace()
        }
    }
    
    companion object {
        fun install(context: Context) {
            if (Thread.getDefaultUncaughtExceptionHandler() !is GlobalCrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(context))
            }
        }
    }
}
