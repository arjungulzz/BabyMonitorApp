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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Ads
        com.google.android.gms.ads.MobileAds.initialize(this) {}

        findViewById<View>(R.id.cardBabyStation).setOnClickListener {
            checkPermissionsAndStart()
        }

        findViewById<View>(R.id.cardParentStation).setOnClickListener {
            startActivity(Intent(this, ParentStationActivity::class.java))
        }

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
    }

    private fun sendEmail(subject: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:") // only email apps should handle this
            putExtra(Intent.EXTRA_EMAIL, arrayOf("developer@example.com")) // Placeholder
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No email client found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMonetization() {
        val cardGoPro = findViewById<View>(R.id.cardGoPro)
        val adView = findViewById<com.google.android.gms.ads.AdView>(R.id.adView)

        if (com.example.babymonitor.billing.BillingManager.isProUser(this)) {
            // Pro Mode
            cardGoPro.visibility = View.GONE
            adView.visibility = View.GONE
        } else {
            // Free Mode
            cardGoPro.visibility = View.VISIBLE
            adView.visibility = View.VISIBLE
            val adRequest = com.google.android.gms.ads.AdRequest.Builder().build()
            adView.loadAd(adRequest)

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
