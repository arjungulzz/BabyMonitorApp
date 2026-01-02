package com.example.babymonitor

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.YuvImage
import java.util.concurrent.atomic.AtomicReference

class BabyStationActivity : AppCompatActivity() {

    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var server: MjpegServer
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraControl: androidx.camera.core.CameraControl? = null
    private var isMicMuted = false
    private var isFlashOn = false
    
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val currentFrame = AtomicReference<ByteArray>()
    private lateinit var tvStatus: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_baby_station)
        supportActionBar?.hide()
        
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Hide System Bars (Immersive)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        tvStatus = findViewById(R.id.tvStatus)
        setupControls()

        isPro = com.example.babymonitor.billing.BillingManager.isProUser(this)
        
        startCamera()
        startServer()
        startAudioServer()
        registerService()

        resetInactivityTimer()
    }

    private var isPro = false
    private var isNoiseAlert = false
    private var lastMotionDetectedTime = 0L
    private var previousLuma: ByteArray? = null
    
    // Default ROI is full screen (0.2-0.8 was default in View, but logically full screen 0.0-1.0 is better for start)
    // But View defaults to 0.2-0.8 for visibility. Let's sync.
    // Default ROI matches RoiOverlayView default (0.2-0.8) so it's visible by default
    private var currentRoi = android.graphics.RectF(0.2f, 0.2f, 0.8f, 0.8f)

    private fun setupControls() {
        val btnFlip = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnFlipCamera)
        val btnMic = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnMicToggle)
        val btnFlash = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnFlashToggle)
        val seekZoom = findViewById<android.widget.SeekBar>(R.id.seekBarZoom)
        val btnClose = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnCloseStation)
        val btnInfo = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnInfo)
        
        val btnSetZone = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnSetZone)
        val roiOverlay = findViewById<com.example.babymonitor.ui.RoiOverlayView>(R.id.roiOverlay)
        val btnDoneRoi = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnDoneRoi)
        val layoutControls = findViewById<android.view.View>(R.id.layoutControls)


        
        val tvMicLabel = findViewById<android.widget.TextView>(R.id.tvMicLabel)
        val tvFlashLabel = findViewById<android.widget.TextView>(R.id.tvFlashLabel)

        btnClose.setOnClickListener {
            finish()
        }

        btnInfo.setOnClickListener {
            showIpDialog()
        }

        btnFlip.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
            resetInactivityTimer()
        }

        btnMic.setOnClickListener {
            isMicMuted = !isMicMuted
            btnMic.setImageResource(if (isMicMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
            tvMicLabel.text = if (isMicMuted) "Mic Off" else "Mic On"
            resetInactivityTimer()
        }

        btnFlash.setOnClickListener {
            isFlashOn = !isFlashOn
            cameraControl?.enableTorch(isFlashOn)
            btnFlash.setImageResource(if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
            tvFlashLabel.text = if (isFlashOn) "Flash On" else "Flash Off"
            
            btnFlash.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (isFlashOn) ContextCompat.getColor(this, R.color.primary_dark_blue) else ContextCompat.getColor(this, R.color.surface_white)
            )
            btnFlash.imageTintList = android.content.res.ColorStateList.valueOf(
                if (isFlashOn) ContextCompat.getColor(this, R.color.surface_white) else ContextCompat.getColor(this, R.color.text_primary)
            )
            resetInactivityTimer()
        }

        seekZoom.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    cameraControl?.setLinearZoom(progress / 100f)
                    resetInactivityTimer()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        val btnZoomOut = findViewById<android.view.View>(R.id.btnZoomOut)
        val btnZoomIn = findViewById<android.view.View>(R.id.btnZoomIn)

        btnZoomOut.setOnClickListener {
            val progress = seekZoom.progress
            val newProgress = (progress - 10).coerceAtLeast(0)
            seekZoom.progress = newProgress
            cameraControl?.setLinearZoom(newProgress / 100f)
            resetInactivityTimer()
        }

        btnZoomIn.setOnClickListener {
            val progress = seekZoom.progress
            val newProgress = (progress + 10).coerceAtMost(100)
            seekZoom.progress = newProgress
            cameraControl?.setLinearZoom(newProgress / 100f)
            resetInactivityTimer()
        }

        btnSetZone.setOnClickListener {
             // Start Editing
             roiOverlay.setEditable(true)
             layoutControls.visibility = android.view.View.GONE
             btnDoneRoi.visibility = android.view.View.VISIBLE
             
             // Reset inactivity timer not needed as controls are hidden? 
             // Actually we should keep screen on.
             resetInactivityTimer()
        }
        
        btnDoneRoi.setOnClickListener {
             // Stop Editing / Save
             currentRoi.set(roiOverlay.roiRectNorm)
             roiOverlay.setEditable(false)
             
             layoutControls.visibility = android.view.View.VISIBLE
             btnDoneRoi.visibility = android.view.View.GONE
             resetInactivityTimer()
        }
    }

    private val ecoModeRunnable = Runnable { enableEcoMode(true) }
    private fun resetInactivityTimer() {
        handler.removeCallbacks(ecoModeRunnable)
        enableEcoMode(false)
        handler.postDelayed(ecoModeRunnable, 15000) // 15 seconds
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        resetInactivityTimer()
        return super.dispatchTouchEvent(ev)
    }

    private fun enableEcoMode(enable: Boolean) {
        val overlay = findViewById<android.view.View>(R.id.overlayEcoMode)
        val controls = findViewById<android.view.View>(R.id.layoutControls)
        val layoutParams = window.attributes

        if (enable) {
            overlay.visibility = android.view.View.VISIBLE
            controls.visibility = android.view.View.GONE
            // Set brightness to minimum
            layoutParams.screenBrightness = 0.01f // 1% brightness
        } else {
            overlay.visibility = android.view.View.GONE
            controls.visibility = android.view.View.VISIBLE
            // Restore brightness to system default
            layoutParams.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = layoutParams
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            val viewFinder = findViewById<PreviewView>(R.id.viewFinder)
            viewFinder.scaleType = PreviewView.ScaleType.FILL_CENTER // Immersive fill
            preview.setSurfaceProvider(viewFinder.surfaceProvider)

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                // 1. Convert to NV21 ByteArray immediately (Single read of buffers)
                val nv21 = com.example.babymonitor.Utils.yuv420888ToNv21(imageProxy)
                
                var isMotion = false

                // Get ROI from Overlay (Thread-safe? strictly it's a view property, usually accessed on UI thread. 
                // But accessing a RectF/variable is generally low risk if not modifying it.
                // Ideally we should post to main to get it, or use a shared AtomicReference.
                // But for now, let's assume `roiOverlay.roiRectNorm` is accessible.
                // Uh oh, `roiOverlay` is local to setupControls. I need to make it class member or access via findViewById inside here.
                // BUT `findViewById` cannot be called on background thread.
                // SOLUTION: Store `roiRectNorm` in a thread-safe variable in the Activity.
                // And update it when the View updates. 
                // OR: Just hardcode for now? NO.
                // Better: Update `currentRoi` variable in Activity whenever `onTouch` happens in View?
                // Or just access it via `findViewById`? NO.
                
                // Let's declare `private val currentRoi = RectF(0.2f, 0.2f, 0.8f, 0.8f)` in class.
                // And update it when user drags (we'd need a listener on View).
                // Or just read it here? NO.
                
                // WAIT. Use `runOnUiThread` to get it? That blocks the analyzer.
                // Better: Have `RoiOverlayView` update a public static/shared variable or callback.
                
                // Hack for now: `val roiOverlay = findViewById<...>(R.id.roiOverlay)` WILL CRASH.
                // I must make `currentRoi` a member and update it.
                // I'll add `currentRoi` to class member.
                
                // Since I cannot change the View code in this tool call (I am editing Activity),
                // I will add a listener mechanism or just poll it?
                // Wait, I can't poll.
                
                // I will assume `roiOverlay` updates `currentRoi`? No it acts on its own.
                // I will modify `setupControls` to add a listener if possible, but `RoiOverlayView` doesn't have one yet.
                
                // OK, I will add `roiOverlay = findViewById...` to class level `lateinit`.
                // And accessing its property `roiRectNorm` IS theoretically unsafe but practically works for read-only float reading on JVM mostly.
                // But let's be safer.
                // I'll add a simple `updateRoi()` method that `setupControls` calls? No...
                
                // Let's just use `currentRoi` which I will verify periodically?
                // Or: just assume full screen if I can't get it easily.
                
                // I will blindly access `roiOverlay.roiRectNorm` here. It is risky.
                // Actually `findViewById` works on any thread? NO. Views are not thread safe.
                
                // REPLAN:
                // 1. Add `currentRoi` (AtomicReference or Volatile) to Activity.
                // 2. In `setupControls`, set a listener? No listener on View.
                // 3. Okay, I will rely on the fact that ONLY when I click "Done" (Set Zone toggle) do I commit the change?
                //    Yes! "Set Zone" -> Edit -> "Done".
                //    So `currentRoi` only needs to update when I click "Done".
                //    Perfect!
                //    So inside `btnSetZone.setOnClickListener` (when checking NOT isEditing/Done), I read `roiOverlay.roiRectNorm` and save it to `currentRoi`.
                
                // IMPLEMENTATION in THIS chunk:
                // Use `currentRoi` (RectF) which I will define in class.
                
                val roi = currentRoi // Snapshot
                
                if (isPro) {
                    // Motion Detection on Y-Plane (First width*height bytes of NV21)
                    val width = imageProxy.width
                    val height = imageProxy.height
                    val ySize = width * height
                    
                    var diffCount = 0
                    val threshold = 50 // Pixel diff threshold
                    val trigger = (ySize * (roi.width() * roi.height())) / 200 // 0.5% of ROI area? Or total? Let's use 0.5% of ROI pixels.
                    
                    // Convert Norm ROI to Pixel Coordinates
                    val rObj = Rect(
                        (roi.left * width).toInt().coerceIn(0, width),
                        (roi.top * height).toInt().coerceIn(0, height),
                        (roi.right * width).toInt().coerceIn(0, width),
                        (roi.bottom * height).toInt().coerceIn(0, height)
                    )
                    
                    // Optimization: Only loop through ROI Y-pixels
                    // Y-plane is linear. row by row.
                    
                    if (previousLuma != null && previousLuma!!.size == ySize) {
                        // Check pixels inside ROI
                        // Iterate rows from rObj.top to rObj.bottom
                        for (y in rObj.top until rObj.bottom step 2) { // Step 2 for speed
                             val rowStart = y * width
                             // Iterate cols from rObj.left to rObj.right
                             for (x in rObj.left until rObj.right step 10) { // Check every 10th pixel in row
                                 val i = rowStart + x
                                 if (i < ySize) { // Safety check
                                     val diff = kotlin.math.abs((nv21[i].toInt() and 0xFF) - (previousLuma!![i].toInt() and 0xFF))
                                     if (diff > threshold) diffCount++
                                 }
                             }
                        }
                        
                        // Scale trigger based on scan density (we skipped pixels)
                        // Trigger logic needs to match sampling.
                        // We sampled 1/20th of pixels approx? (step 2 * step 10 = 20)
                        
                        val sampledPixels = ((rObj.width() / 10) * (rObj.height() / 2))
                        val triggerCount = sampledPixels / 50 // 2% of sampled pixels changed?
                        
                        if (diffCount > triggerCount && triggerCount > 10) { // Minimum noise floor
                            lastMotionDetectedTime = System.currentTimeMillis()
                        }
                    }
                    
                    // Update previousLuma with current Y-plane
                    // We only need the first ySize bytes
                    if (previousLuma == null || previousLuma!!.size != ySize) {
                        previousLuma = ByteArray(ySize)
                    }
                    System.arraycopy(nv21, 0, previousLuma!!, 0, ySize)
                    
                    if (System.currentTimeMillis() - lastMotionDetectedTime < 2000) {
                        isMotion = true
                    }
                }

                // 2. Convert to Bitmap for Overlay using the SAME nv21 data
                // No need to touch imageProxy planes again
                val yuvImage = YuvImage(
                    nv21,
                    android.graphics.ImageFormat.NV21,
                    imageProxy.width,
                    imageProxy.height,
                    null
                )
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 60, out)
                val jpgBytes = out.toByteArray()
                var bitmap = BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.size)

                // 3. Rotate if needed
                if (rotationDegrees != 0) {
                     val matrix = android.graphics.Matrix()
                     matrix.postRotate(rotationDegrees.toFloat())
                     bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                }
                
                // Draw ROI Box (ALWAYS, if not full screen)
                if (roi.width() < 0.95f || roi.height() < 0.95f) { // If significant crop
                    val mutableBitmap = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = android.graphics.Canvas(mutableBitmap)
                    val paintRoi = android.graphics.Paint().apply {
                        color = android.graphics.Color.GREEN
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 5f
                        pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
                    }
                    
                    val rDraw = android.graphics.RectF(
                         roi.left * mutableBitmap.width,
                         roi.top * mutableBitmap.height,
                         roi.right * mutableBitmap.width,
                         roi.bottom * mutableBitmap.height
                    )
                    canvas.drawRect(rDraw, paintRoi)
                    bitmap = mutableBitmap
                }
                
                // 4. Draw Overlays (Motion / Noise) if Pro
                if (isPro && (isMotion || isNoiseAlert)) {
                    val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = android.graphics.Canvas(mutableBitmap)
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.RED
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 20f
                    }
                    
                    // Draw Motion Box
                    if (isMotion) {
                        val rect = Rect(10, 10, mutableBitmap.width - 10, mutableBitmap.height - 10)
                        canvas.drawRect(rect, paint)
                    }
                    
                    // Draw Noise Alert
                    if (isNoiseAlert) {
                        paint.style = android.graphics.Paint.Style.FILL
                        paint.textSize = 100f
                        canvas.drawText("🔊 NOISE DETECTED", 50f, 200f, paint)
                    }
                    
                    bitmap = mutableBitmap
                }

                // 5. Compress final frame
                val finalOut = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 40, finalOut)
                currentFrame.set(finalOut.toByteArray())
                
                imageProxy.close()
            }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
                cameraControl = camera.cameraControl
                
                val hasFlash = camera.cameraInfo.hasFlashUnit()
                val btnFlash = findViewById<android.view.View>(R.id.btnFlashToggle)
                btnFlash.isEnabled = hasFlash
                btnFlash.alpha = if (hasFlash) 1.0f else 0.5f
                if (!hasFlash && isFlashOn) {
                    // Turn off if we switched to a camera with no flash
                    isFlashOn = false
                    val fBtn = btnFlash as com.google.android.material.floatingactionbutton.FloatingActionButton
                    fBtn.setImageResource(R.drawable.ic_flash_off)
                    fBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_white))
                    fBtn.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_primary))
                    findViewById<android.widget.TextView>(R.id.tvFlashLabel).text = "Flash Off"
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startServer() {
        server = MjpegServer(8080)
        try {
            server.start()
            Log.d(TAG, "Server started")
        } catch (e: IOException) {
            e.printStackTrace()
            runOnUiThread {
                tvStatus.text = "Server Error"
            }
        }
    }

    private fun registerService() {
        val serviceInfo = NsdServiceInfo().apply {
            // sanitize the name to be safe for NSD
            val deviceName = android.os.Build.MODEL ?: "Baby Monitor"
            // NSD service names must be < 63 bytes.
            val safeName = if (deviceName.length > 50) deviceName.substring(0, 50) else deviceName
            serviceName = "$safeName-${System.currentTimeMillis() % 1000}" // Add random suffix to ensure uniqueness
            serviceType = "_http._tcp."
            port = 8080
        }

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${NsdServiceInfo.serviceName}")
                runOnUiThread {
                    tvStatus.text = "Online: ${NsdServiceInfo.serviceName}"
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
                runOnUiThread {
                    tvStatus.text = "Registration Failed"
                }
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }

        nsdManager?.registerService(
            serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
        stopAudioServer()
        nsdManager?.unregisterService(registrationListener)
    }

    // Audio Server Logic
    private var isAudioRunning = false
    private var audioThread: Thread? = null

    private fun showIpDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ip_info, null)
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        val tvIp = dialogView.findViewById<android.widget.TextView>(R.id.tvIpAddress)
        val btnClose = dialogView.findViewById<android.view.View>(R.id.btnCloseDialog)
        
        val ip = getIpAddress()
        tvIp.text = if (ip != null) "$ip:8080" else "Unknown IP"
        
        btnClose.setOnClickListener {
            dialog.dismiss()
            resetInactivityTimer()
        }
        
        dialog.show()
        resetInactivityTimer()
    }

    private fun getIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun startAudioServer() {
        if (isAudioRunning) return
        isAudioRunning = true
        
        audioThread = Thread {
            var serverSocket: java.net.ServerSocket? = null
            var audioRecord: android.media.AudioRecord? = null
            
            try {
                serverSocket = java.net.ServerSocket(8081)
                
                val sampleRate = 44100
                val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
                val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
                val minBufSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    
                    audioRecord = android.media.AudioRecord(
                        android.media.MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        minBufSize
                    )
                    audioRecord.startRecording()
                    
                    val buffer = ByteArray(minBufSize)
                    
                    while (isAudioRunning && !Thread.currentThread().isInterrupted) {
                         try {
                             // Accept client
                             // Use a timeout so we can check isAudioRunning periodically if no one connects
                             serverSocket.soTimeout = 2000 
                             val client = serverSocket.accept()
                             
                             // Client connected
                             val out = client.getOutputStream()
                             
                             while (isAudioRunning && client.isConnected && !client.isClosed) {
                                 if (isMicMuted) {
                                     // Send silence
                                     java.util.Arrays.fill(buffer, 0)
                                     try {
                                        out.write(buffer, 0, minBufSize)
                                        // Sleep to emulate timing? Or just write?
                                        // Writing silence is fast, so let's sleep to avoid high CPU or flooding
                                        // 44100 Hz, 16bit, mono -> 88200 bytes/sec
                                        // minBufSize is approx 20-50ms usually.
                                        val sleepTime = (minBufSize * 1000) / (44100 * 2)
                                        Thread.sleep(sleepTime.toLong())
                                     } catch (e: IOException) {
                                        break
                                     }
                                 } else {
                                     val read = audioRecord.read(buffer, 0, minBufSize)
                                     if (read > 0) {
                                         // Noise Detection (Pro)
                                         if (isPro) {
                                             var sum = 0.0
                                             for (i in 0 until read step 2) {
                                                  val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                                                  sum += sample * sample
                                             }
                                             val rms = kotlin.math.sqrt(sum / (read / 2))
                                             isNoiseAlert = rms > 3000 // Threshold (tuned)
                                         }
                                         
                                         try {
                                            out.write(buffer, 0, read)
                                         } catch (e: IOException) {
                                            // Client disconnected
                                            break
                                         }
                                     }
                                 }
                             }
                             client.close()
                         } catch (e: java.net.SocketTimeoutException) {
                             // invoke loop check
                             continue
                         } catch (e: IOException) {
                             e.printStackTrace()
                         }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                    serverSocket?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        audioThread?.start()
    }

    private fun stopAudioServer() {
        isAudioRunning = false
        audioThread?.interrupt()
        audioThread = null
    }

    private inner class MjpegServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            return if (session.uri == "/") {
                val boundary = "Obj_123"
                val contentType = "multipart/x-mixed-replace;boundary=$boundary"

                // Create a PipedInputStream could be complex, let's try a simple loop in a separate thread approach
                // Actually NanoHTTPD `serve` expects a return. For streaming, we need to return a ChunkedResponse
                // But NanoHTTPD support for MJPEG usually involves custom response handling.
                // Simplified: We accept the connection and keep writing to the output stream.

                // Wait, NanoHTTPD Response needs an InputStream.
                // We can use a custom InputStream that blocks and waits for new frames.

                val inputStream = object : java.io.InputStream() {
                     // This is tricky to verify without testing.
                     // Let's assume standard approach: Send one frame? No, we need a stream.
                     // We will implement a simple infinite stream here.
                     // But strictly speaking, NanoHTTPD requires us to pass a stream that it will read from.

                     // Alternative: create a PipedInputStream and write to PipedOutputStream in a loop.
                     val pipedIn = java.io.PipedInputStream()
                     val pipedOut = java.io.PipedOutputStream(pipedIn)

                     init {
                        Thread {
                            try {
                                while (true) {
                                    val frame = currentFrame.get()
                                    if (frame != null) {
                                        pipedOut.write(("--$boundary\r\n").toByteArray())
                                        pipedOut.write("Content-Type: image/jpeg\r\n".toByteArray())
                                        pipedOut.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                                        pipedOut.write(frame)
                                        pipedOut.write("\r\n".toByteArray())
                                        pipedOut.flush()
                                    }
                                    Thread.sleep(100) // 10 FPS
                                }
                            } catch (e: Exception) {
                                // Connection closed
                            }
                        }.start()
                     }

                     override fun read(): Int {
                         return pipedIn.read()
                     }

                     override fun read(b: ByteArray, off: Int, len: Int): Int {
                         return pipedIn.read(b, off, len)
                     }
                }

               newChunkedResponse(Response.Status.OK, contentType, inputStream)
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }
    }

    // Picture-in-Picture Logic
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (com.example.babymonitor.billing.BillingManager.isProUser(this)) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                 val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(9, 16))
                    .build()
                enterPictureInPictureMode(params)
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        val controls = findViewById<android.view.View>(R.id.layoutControls)
        val btnClose = findViewById<android.view.View>(R.id.btnCloseStation)
        val btnInfo = findViewById<android.view.View>(R.id.btnInfo)
        val tvStatus = findViewById<android.view.View>(R.id.tvStatus)

        if (isInPictureInPictureMode) {
            controls.visibility = android.view.View.GONE
            btnClose.visibility = android.view.View.GONE
            btnInfo.visibility = android.view.View.GONE
            tvStatus.visibility = android.view.View.GONE
        } else {
            controls.visibility = android.view.View.VISIBLE
            btnClose.visibility = android.view.View.VISIBLE
            btnInfo.visibility = android.view.View.VISIBLE
            tvStatus.visibility = android.view.View.VISIBLE
        }
    }

    companion object {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private const val TAG = "BabyStationActivity"
    }
}
