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

        findViewById<View>(R.id.cardBabyStation).setOnClickListener {
            checkPermissionsAndStart()
        }

        findViewById<View>(R.id.cardParentStation).setOnClickListener {
            startActivity(Intent(this, ParentStationActivity::class.java))
        }
    }

    private fun checkPermissionsAndStart() {
        if (allPermissionsGranted()) {
            startActivity(Intent(this, BabyStationActivity::class.java))
        } else {
             // Check if we should show rationale
             if (ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.CAMERA)) {
                 android.app.AlertDialog.Builder(this)
                     .setTitle("Camera Permission Needed")
                     .setMessage("This app uses the camera to function as a Baby Monitor. Please grant access.")
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
            android.Manifest.permission.CAMERA
        )
    }
}
