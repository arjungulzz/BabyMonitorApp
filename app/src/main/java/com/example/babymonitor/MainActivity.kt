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
        
        // Handle edge-to-edge display and system bars
        setupWindowInsets()
        
        // Register Live UI Receiver
        val filter = android.content.IntentFilter("com.example.babymonitor.ACTION_REFRESH_UI")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiUpdateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiUpdateReceiver, filter)
        }

        // Initialize Ads on background thread to avoid blocking UI
        Thread {
            com.google.android.gms.ads.MobileAds.initialize(this) {}
        }.start()

        findViewById<View>(R.id.btnFeedback).setOnClickListener {
            animateButtonPress(it)
            sendEmail("Feedback: Baby Monitor App")
        }

        findViewById<View>(R.id.btnReport).setOnClickListener {
            animateButtonPress(it)
            sendEmail("Issue Report: Baby Monitor App")
        }

        findViewById<View>(R.id.btnSupport).setOnClickListener {
            animateButtonPress(it)
             val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.buymeacoffee.com"))
             try { startActivity(browserIntent) } catch (e: Exception) { e.printStackTrace() }
        }

        findViewById<View>(R.id.btnRate).setOnClickListener {
            animateButtonPress(it)
            requestInAppReview()
        }

        findViewById<View>(R.id.btnAbout).setOnClickListener {
            animateButtonPress(it)
            android.app.AlertDialog.Builder(this)
                .setTitle("About Baby Monitor")
                .setMessage("Version 1.0\n\nA simple, secure baby monitor for your home.\n\nMade with ❤️")
                .setPositiveButton("OK", null)
                .show()
        }

        setupMonetization()
        
        // Auto-restore Pro status (background check)
        com.example.babymonitor.billing.BillingManager.verifyProStatus(this)

        // Delay animations slightly to let layout settle and reduce startup jank
        window.decorView.postDelayed({
            setupEnterAnimations()
        }, 100)
        
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
        val tvProTitle = findViewById<android.widget.TextView>(R.id.tvProTitle)
        val tvProSubtitle = findViewById<android.widget.TextView>(R.id.tvProSubtitle)

        if (com.example.babymonitor.billing.BillingManager.isProUser(this)) {
            // Pro Mode
            adView.visibility = android.view.View.GONE
            
            // Update Pro Banner
            tvProTitle.text = "Pro Member"
            tvProSubtitle.text = "Thank you for your support! 👑"
            
            cardGoPro.setOnClickListener {
                android.widget.Toast.makeText(this, "You are a Pro Member! 👑", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            // Free Mode - Load ads after a short delay to avoid blocking UI
            adView.visibility = android.view.View.VISIBLE
            
            // Delay ad loading to avoid blocking initial render
            adView.postDelayed({
                val adRequest = com.google.android.gms.ads.AdRequest.Builder().build()
                adView.loadAd(adRequest)
            }, 200)

            // Default Pro Banner text (already set in XML)
            cardGoPro.setOnClickListener {
                showProPurchaseDialog()
            }
        }
    }

    private fun showProPurchaseDialog() {
        com.example.babymonitor.utils.DialogUtils.showProUpgradeDialog(this) {
            runOnUiThread {
                Toast.makeText(this, "Welcome to Pro Mode!", Toast.LENGTH_SHORT).show()
                setupMonetization() // Refresh UI
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (allPermissionsGranted()) {
            startActivity(Intent(this, BabyStationActivity::class.java))
        } else {
             val prefs = getSharedPreferences("BabyMonitorPerms", android.content.Context.MODE_PRIVATE)
             val firstRequestDone = prefs.getBoolean("first_request_done", false)

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
                 if (firstRequestDone) {
                     // Case 3: User denied permanently or System blocked it
                     android.app.AlertDialog.Builder(this)
                         .setTitle("Permissions Blocked")
                         .setMessage("Camera and Audio permissions are required to use this feature.\n\nPlease enable them in Settings.")
                         .setPositiveButton("Open Settings") { _, _ ->
                             val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                             intent.data = android.net.Uri.fromParts("package", packageName, null)
                             startActivity(intent)
                         }
                         .setNegativeButton("Cancel", null)
                         .show()
                 } else {
                     // Case 1: First time
                     prefs.edit().putBoolean("first_request_done", true).apply()
                     ActivityCompat.requestPermissions(
                         this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                     )
                 }
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

    private fun requestInAppReview() {
        val manager = com.google.android.play.core.review.ReviewManagerFactory.create(this)
        val request = manager.requestReviewFlow()
        
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                // Got the ReviewInfo object
                val reviewInfo = task.result
                val flow = manager.launchReviewFlow(this, reviewInfo)
                flow.addOnCompleteListener {
                    // Review flow has finished
                    android.util.Log.d("InAppReview", "Review flow completed")
                    
                    // In development/testing, the dialog may not show
                    // Offer alternative to open Play Store
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        showPlayStoreOption()
                    }, 500)
                }
            } else {
                // There was an error, fallback to Play Store
                android.util.Log.e("InAppReview", "Review flow failed", task.exception)
                showPlayStoreOption()
            }
        }
    }
    
    private fun showPlayStoreOption() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Rate Baby Monitor")
            .setMessage("Would you like to rate us on the Play Store?")
            .setPositiveButton("Rate Now") { _, _ ->
                openPlayStore()
            }
            .setNegativeButton("Maybe Later", null)
            .show()
    }
    
    private fun openPlayStore() {
        try {
            // Try to open Play Store app
            val uri = android.net.Uri.parse("market://details?id=$packageName")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: android.content.ActivityNotFoundException) {
            // Play Store app not installed, open in browser
            val uri = android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }
    
    private fun setupEnterAnimations() {
        // Hero section animations
        val tvEmoji = findViewById<android.widget.TextView>(R.id.tvEmoji)
        val tvTitle = findViewById<android.widget.TextView>(R.id.tvTitle)
        val tvTagline = findViewById<android.widget.TextView>(R.id.tvTagline)
        
        // Cards
        val cardBaby = findViewById<View>(R.id.cardBabyStation)
        val cardParent = findViewById<View>(R.id.cardParentStation)
        val cardPro = findViewById<View>(R.id.cardGoPro)
        
        // Support section
        val supportContainer = findViewById<View>(R.id.supportContainer)
        val btnSupport = findViewById<View>(R.id.btnSupport)
        
        // Animate emoji with scale + fade
        tvEmoji.animate()
            .alpha(1f)
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(600)
            .setStartDelay(100)
            .withEndAction {
                tvEmoji.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .start()
            }
            .start()
        
        // Animate title
        tvTitle.animate()
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(300)
            .start()
        
        // Animate tagline
        tvTagline.animate()
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(450)
            .start()
        
        // Animate cards with stagger
        animateCard(cardBaby, 600)
        animateCard(cardParent, 750)
        
        // Pro banner needs larger initial translation to avoid overlap
        cardPro.translationY = 100f
        animateCard(cardPro, 900)
        
        // Animate support section
        supportContainer.animate()
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(1050)
            .start()
        
        btnSupport.animate()
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(1150)
            .start()
    }
    
    private fun animateButtonPress(view: View) {
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }
    
    private fun animateCard(card: View, startDelay: Long) {
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(startDelay)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun setupWindowInsets() {
        val adView = findViewById<com.google.android.gms.ads.AdView>(R.id.adView)
        
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(adView) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val layoutParams = view.layoutParams as android.widget.FrameLayout.LayoutParams
            layoutParams.bottomMargin = systemBars.bottom + resources.getDimensionPixelSize(R.dimen.spacing_md)
            view.layoutParams = layoutParams
            insets
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
