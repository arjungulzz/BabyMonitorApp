package com.example.babymonitor

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val uiUpdateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.example.babymonitor.ACTION_REFRESH_UI") {
                android.util.Log.d("BabyMonitor", "MainActivity: Received ACTION_REFRESH_UI Broadcast")
                // Delay check to allow Service to fully stop (Race Condition Fix)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    setupResumeUI()
                }, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Register Live UI Receiver
        val filter = android.content.IntentFilter("com.example.babymonitor.ACTION_REFRESH_UI")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiUpdateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiUpdateReceiver, filter)
        }

        // Initialize Ads
        com.google.android.gms.ads.MobileAds.initialize(this) {}

        findViewById<View>(R.id.btnFeedback).setOnClickListener {
            sendEmail("Feedback: Baby Monitor App")
        }

        findViewById<View>(R.id.btnReport).setOnClickListener {
            sendEmail("Issue Report: Baby Monitor App")
        }

        findViewById<View>(R.id.btnSupport).setOnClickListener {
             val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.buymeacoffee.com"))
             try { startActivity(browserIntent) } catch (e: Exception) { e.printStackTrace() }
        }

        findViewById<View>(R.id.btnAbout).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("About Baby Monitor")
                .setMessage("Version 1.0\n\nA simple, secure baby monitor for your home.\n\nMade with ❤️")
                .setPositiveButton("OK", null)
                .show()
        }

        setupMonetization()
        checkCrashReport()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(uiUpdateReceiver)
    }

    override fun onResume() {
        super.onResume()
        setupResumeUI()
    }

    private fun setupResumeUI() {
        val isBabyActive = isServiceRunning(BabyMarkerService::class.java)
        val isStreamActive = isServiceRunning(ParentMarkerService::class.java)
        android.util.Log.d("BabyMonitor", "MainActivity: setupResumeUI. BabyActive=$isBabyActive, StreamActive=$isStreamActive")

        val cardBaby = findViewById<android.view.View>(R.id.cardBabyStation)
        val cardParent = findViewById<android.view.View>(R.id.cardParentStation)
        val tvBabyLabel = findViewById<android.widget.TextView>(R.id.tvBabyStationLabel)
        val tvParentLabel = findViewById<android.widget.TextView>(R.id.tvParentStationLabel)

        // Default State
        cardBaby.alpha = 1.0f
        cardBaby.isEnabled = true
        tvBabyLabel.text = "Baby\nStation"
        cardBaby.setOnClickListener { checkPermissionsAndStart() }

        cardParent.alpha = 1.0f
        cardParent.isEnabled = true
        tvParentLabel.text = "Parent\nStation"
        cardParent.setOnClickListener { startActivity(Intent(this, ParentStationActivity::class.java)) }

        // Smart Logic
        if (isBabyActive) {
            tvBabyLabel.text = "Resume\nBaby Station"
            // Disable Parent Station to avoid conflict
            cardParent.alpha = 0.5f 
            cardParent.isEnabled = false
        } else if (isStreamActive) {
            tvParentLabel.text = "Resume\nParent Station"
            // Redirect directly to StreamActivity (Resume)
            cardParent.setOnClickListener { 
                startActivity(Intent(this, StreamActivity::class.java)) 
            }
            // Disable Baby Station to avoid conflict
            cardBaby.alpha = 0.5f
            cardBaby.isEnabled = false
        }
    }
    
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(android.app.ActivityManager::class.java)
        // Check running services - this is allowed for own application services even in newer Android versions
        for (service in activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun checkCrashReport() {
        val file = java.io.File(getExternalFilesDir(null), "crash_log.txt")
        if (file.exists()) {
            val logContent = file.readText()
            
            // Clear the file so we don't prompt again
            file.delete()
            
            android.app.AlertDialog.Builder(this)
                .setTitle("App Recovered from Crash")
                .setMessage("The app encountered an error and restarted. Would you like to send the crash report to the developer to help fix it?")
                .setPositiveButton("Send Report") { _, _ ->
                    sendCrashReport(logContent)
                }
                .setNegativeButton("Ignore", null)
                .show()
        }
    }

    private fun sendCrashReport(log: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf("arjungulyani@gmail.com")) // Developer email
            putExtra(Intent.EXTRA_SUBJECT, "Crash Report: Baby Monitor App")
            putExtra(Intent.EXTRA_TEXT, "Device Info:\nModel: ${android.os.Build.MODEL}\nAndroid: ${android.os.Build.VERSION.RELEASE}\n\nStack Trace:\n$log")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No email client found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendEmail(subject: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:") // only email apps should handle this
            putExtra(Intent.EXTRA_EMAIL, arrayOf("arjungulyani@gmail.com")) 
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No email client found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMonetization() {
        val cardGoPro = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardGoPro)
        val adView = findViewById<com.google.android.gms.ads.AdView>(R.id.adView)

        // Access inner views dynamically since they don't have unique IDs
        val layout = cardGoPro.getChildAt(0) as android.widget.LinearLayout
        val tvGoPro = layout.getChildAt(1) as android.widget.TextView
        val imgGoPro = layout.getChildAt(0) as android.widget.ImageView

        if (com.example.babymonitor.billing.BillingManager.isProUser(this)) {
            // Pro Mode
            adView.visibility = android.view.View.GONE
            
            // Show Pro Badge
            cardGoPro.visibility = android.view.View.VISIBLE
            cardGoPro.strokeColor = android.graphics.Color.parseColor("#FFD700") // Gold
            cardGoPro.setCardBackgroundColor(android.graphics.Color.parseColor("#10FFD700")) // Subtle Gold Tint
            
            tvGoPro.text = "Pro\nMember"
            tvGoPro.setTextColor(android.graphics.Color.parseColor("#FFD700"))
            
            cardGoPro.setOnClickListener {
                android.widget.Toast.makeText(this, "You are a Pro Member! \uD83D\uDC51", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            // Free Mode
            adView.visibility = android.view.View.VISIBLE
            val adRequest = com.google.android.gms.ads.AdRequest.Builder().build()
            adView.loadAd(adRequest)

            cardGoPro.visibility = android.view.View.VISIBLE
            cardGoPro.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.surface_white))
            tvGoPro.text = "Go Pro\nPlan"
             // Reset text color
             tvGoPro.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary))

            cardGoPro.setOnClickListener {
                showProPurchaseDialog()
            }
        }
    }

    private fun showProPurchaseDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Go Pro")
            .setMessage("Remove ads and support development for just $4.99!") // Mock price
            .setIcon(R.drawable.ic_crown)
            .setPositiveButton("Purchase (Mock)") { _, _ ->
                com.example.babymonitor.billing.BillingManager.purchasePro(this) {
                    runOnUiThread {
                        Toast.makeText(this, "Welcome to Pro Mode!", Toast.LENGTH_SHORT).show()
                        setupMonetization() // Refresh UI
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPermissionsAndStart() {
        if (allPermissionsGranted()) {
            startActivity(Intent(this, BabyStationActivity::class.java))
        } else {
             // Check if we should show rationale
             if (ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.CAMERA)) {
                 android.app.AlertDialog.Builder(this)
                     .setTitle("Permissions Needed")
                     .setMessage("This app uses the camera and microphone to function as a Baby Monitor. Please grant access.")
                     .setPositiveButton("OK") { _, _ ->
                         ActivityCompat.requestPermissions(
                            this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                        )
                     }
                     .setNegativeButton("Cancel", null)
                     .create()
                     .show()
             } else {
                ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                )
             }
        }
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startActivity(Intent(this, BabyStationActivity::class.java))
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
    }
}
