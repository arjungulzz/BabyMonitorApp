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
    // Default ROI is full screen (invisible by default unless user sets it)
    private var currentRoi = android.graphics.RectF(0.0f, 0.0f, 1.0f, 1.0f)

    private fun setupControls() {
        val btnFlip = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnFlipCamera)
        val btnMic = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnMicToggle)
        val btnFlash = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnFlashToggle)
        val seekZoom = findViewById<android.widget.SeekBar>(R.id.seekBarZoom)
        val btnClose = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnCloseStation)
        val btnInfo = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnInfo)
        
        val btnSetZone = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnSetZone)
        val roiOverlay = findViewById<com.example.babymonitor.ui.RoiOverlayView>(R.id.roiOverlay)
        val layoutControls = findViewById<android.view.View>(R.id.layoutControls)
        val tvZoneLabel = findViewById<android.widget.TextView>(R.id.tvZoneLabel)


        
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

        // Real-time update of ROI for detection
        roiOverlay.onRoiChanged = { rect ->
            currentRoi.set(rect)
        }

        btnSetZone.setOnClickListener {
             val isActive = btnSetZone.contentDescription == "Active"
             
             if (isActive) {
                 // Deactivate ROI (Full Screen Mode)
                 roiOverlay.setEditable(false)
                 currentRoi.set(0.0f, 0.0f, 1.0f, 1.0f) // Back to full screen
                 roiOverlay.invalidate() // Hide box
                 
                 btnSetZone.contentDescription = "Idle"
                 btnSetZone.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_white))
                 btnSetZone.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_primary))
                 tvZoneLabel.text = "Set\nZone"
                 tvZoneLabel.setTextColor(android.graphics.Color.parseColor("#E6FFFFFF"))
             } else {
                 // Activate ROI (Restricted Mode)
                 roiOverlay.setEditable(true)
                 roiOverlay.roiRectNorm.set(0.4f, 0.4f, 0.6f, 0.6f) // Start with center box
                 currentRoi.set(0.4f, 0.4f, 0.6f, 0.6f) // Sync immediately
                 roiOverlay.invalidate() // Show box
                 
                 btnSetZone.contentDescription = "Active"
                 btnSetZone.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_dark_blue))
                 btnSetZone.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_white))
                 tvZoneLabel.text = "Zone\nOn"
                 tvZoneLabel.setTextColor(ContextCompat.getColor(this, R.color.success_green))
             }
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

            // Reusable buffers and objects to reduce GC pressure
            val yuvOutStream = ByteArrayOutputStream()
            val jpegOutStream = ByteArrayOutputStream()
            val rotationMatrix = android.graphics.Matrix()
            val paintRoi = android.graphics.Paint().apply {
                color = android.graphics.Color.GREEN
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 5f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
            }
            val paintAlert = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 20f
            }
            val rectScan = Rect()

            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                // 1. Convert to NV21 ByteArray immediately (Single read of buffers)
                val nv21 = com.example.babymonitor.Utils.yuv420888ToNv21(imageProxy)
                
                var isMotion = false
                val roi = currentRoi // Snapshot
                
                if (isPro) {
                    // Motion Detection on Y-Plane (First width*height bytes of NV21)
                    val width = imageProxy.width
                    val height = imageProxy.height
                    val ySize = width * height
                    
                    var diffCount = 0
                    val threshold = 50 // Pixel diff threshold
                    val trigger = (ySize * (roi.width() * roi.height())) / 200 
                    
                    // Convert Norm ROI to Pixel Coordinates
                    val rObj = Rect(
                        (roi.left * width).toInt().coerceIn(0, width),
                        (roi.top * height).toInt().coerceIn(0, height),
                        (roi.right * width).toInt().coerceIn(0, width),
                        (roi.bottom * height).toInt().coerceIn(0, height)
                    )
                    
                    if (previousLuma != null && previousLuma!!.size == ySize) {
                        // Check pixels inside ROI
                        for (y in rObj.top until rObj.bottom step 2) {
                             val rowStart = y * width
                             for (x in rObj.left until rObj.right step 10) { 
                                 val i = rowStart + x
                                 if (i < ySize) { 
                                     val diff = kotlin.math.abs((nv21[i].toInt() and 0xFF) - (previousLuma!![i].toInt() and 0xFF))
                                     if (diff > threshold) diffCount++
                                 }
                             }
                        }
                        
                        val sampledPixels = ((rObj.width() / 10) * (rObj.height() / 2))
                        val triggerCount = sampledPixels / 50 
                        
                        if (diffCount > triggerCount && triggerCount > 10) {
                            lastMotionDetectedTime = System.currentTimeMillis()
                        }
                    }
                    
                    if (previousLuma == null || previousLuma!!.size != ySize) {
                        previousLuma = ByteArray(ySize)
                    }
                    System.arraycopy(nv21, 0, previousLuma!!, 0, ySize)
                    
                    if (System.currentTimeMillis() - lastMotionDetectedTime < 2000) {
                        isMotion = true
                    }
                }

                // 2. Convert to Bitmap for Overlay using the SAME nv21 data
                val yuvImage = YuvImage(
                    nv21,
                    android.graphics.ImageFormat.NV21,
                    imageProxy.width,
                    imageProxy.height,
                    null
                )
                
                yuvOutStream.reset() // Reuse buffer
                rectScan.set(0, 0, imageProxy.width, imageProxy.height)
                yuvImage.compressToJpeg(rectScan, 60, yuvOutStream)
                val jpgBytes = yuvOutStream.toByteArray()
                var bitmap = BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.size)

                // 3. Rotate if needed
                if (rotationDegrees != 0) {
                     rotationMatrix.reset()
                     rotationMatrix.postRotate(rotationDegrees.toFloat())
                     bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotationMatrix, true)
                }
                
                // Draw ROI Box (ALWAYS, if not full screen)
                if (roi.width() < 0.95f || roi.height() < 0.95f) { // If significant crop
                    val mutableBitmap = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = android.graphics.Canvas(mutableBitmap)
                    
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
                    val mutableBitmap = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = android.graphics.Canvas(mutableBitmap)

                    // Draw Motion Box
                    if (isMotion) {
                        rectScan.set(10, 10, mutableBitmap.width - 10, mutableBitmap.height - 10)
                        canvas.drawRect(rectScan, paintAlert)
                    }
                    
                    // Draw Noise Alert
                    if (isNoiseAlert) {
                        paintAlert.style = android.graphics.Paint.Style.FILL
                        paintAlert.textSize = 100f
                        canvas.drawText("🔊 NOISE DETECTED", 50f, 200f, paintAlert)
                        paintAlert.style = android.graphics.Paint.Style.STROKE // Reset
                    }
                    
                    bitmap = mutableBitmap
                }

                // 5. Compress final frame
                jpegOutStream.reset() // Reuse buffer
                bitmap.compress(Bitmap.CompressFormat.JPEG, 40, jpegOutStream)
                currentFrame.set(jpegOutStream.toByteArray())
                
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
                             client.tcpNoDelay = true // Disable Nagle's algorithm for lower latency
                             
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
             val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(9, 16))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        val controls = findViewById<android.view.View>(R.id.layoutControls)
        val topBar = findViewById<android.view.View>(R.id.topBar)
        val gradient = findViewById<android.view.View>(R.id.gradientOverlay)
        val roiOverlay = findViewById<android.view.View>(R.id.roiOverlay)
        val ecoOverlay = findViewById<android.view.View>(R.id.overlayEcoMode)

        if (isInPictureInPictureMode) {
            controls.visibility = android.view.View.GONE
            topBar.visibility = android.view.View.GONE
            gradient.visibility = android.view.View.GONE
            roiOverlay.visibility = android.view.View.GONE
            ecoOverlay.visibility = android.view.View.GONE
            
            // Ensure brightness is restored if Eco Mode was on
            val layoutParams = window.attributes
            layoutParams.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = layoutParams
        } else {
            controls.visibility = android.view.View.VISIBLE
            topBar.visibility = android.view.View.VISIBLE
            gradient.visibility = android.view.View.VISIBLE
            roiOverlay.visibility = android.view.View.VISIBLE
            // Eco Mode remains hidden until re-enabled
            ecoOverlay.visibility = android.view.View.GONE
        }
    }

    companion object {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private const val TAG = "BabyStationActivity"
    }
}
