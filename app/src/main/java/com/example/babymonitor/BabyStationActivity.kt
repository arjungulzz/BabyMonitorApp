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
        
        startCamera()
        startServer()
        startAudioServer()
        registerService()

        resetInactivityTimer()
    }

    private fun setupControls() {
        val btnFlip = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnFlipCamera)
        val btnMic = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnMicToggle)
        val btnFlash = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnFlashToggle)
        val seekZoom = findViewById<android.widget.SeekBar>(R.id.seekBarZoom)

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
            resetInactivityTimer()
        }

        btnFlash.setOnClickListener {
            isFlashOn = !isFlashOn
            cameraControl?.enableTorch(isFlashOn)
            btnFlash.setImageResource(if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
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
                // Convert YUV to JPEG
                val yuvImage = YuvImage(
                    com.example.babymonitor.Utils.yuv420888ToNv21(imageProxy),
                    android.graphics.ImageFormat.NV21,
                    imageProxy.width,
                    imageProxy.height,
                    null
                )
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 40, out)
                val bytes = out.toByteArray()

                if (rotationDegrees != 0) {
                     val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                     val matrix = android.graphics.Matrix()
                     matrix.postRotate(rotationDegrees.toFloat())
                     val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                     val out2 = ByteArrayOutputStream()
                     rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 40, out2)
                     currentFrame.set(out2.toByteArray())
                } else {
                     currentFrame.set(bytes)
                }

                imageProxy.close()
            }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
                cameraControl = camera.cameraControl
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

    companion object {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private const val TAG = "BabyStationActivity"
    }
}
