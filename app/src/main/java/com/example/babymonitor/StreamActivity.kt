package com.example.babymonitor

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class StreamActivity : AppCompatActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream)
        
        android.util.Log.d("BabyMonitor", "StreamActivity: onCreate. SDK=${android.os.Build.VERSION.SDK_INT}")

        supportActionBar?.hide()

        isRunning = true


        
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide System Bars (Immersive)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val url = intent.getStringExtra("STREAM_URL") ?: return

        val webView = findViewById<WebView>(R.id.webView)
        val layoutError = findViewById<android.view.View>(R.id.layoutError)
        val btnBack = findViewById<android.view.View>(R.id.btnBack)
        val btnDisconnect = findViewById<android.view.View>(R.id.btnDisconnect)
        
        val btnStreamMicToggle = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnStreamMicToggle)
        val tvStreamMicLabel = findViewById<android.widget.TextView>(R.id.tvStreamMicLabel)
        
        val btnStreamFlashToggle = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnStreamFlashToggle)
        val tvStreamFlashLabel = findViewById<android.widget.TextView>(R.id.tvStreamFlashLabel)
        
        val tvBatteryLabel = findViewById<android.widget.TextView>(R.id.tvBatteryLabel)
        val tvAlertBadge = findViewById<android.widget.TextView>(R.id.tvAlertBadge)

        val baseUrl = url.removeSuffix("/")

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelServiceAndBroadcast()
                finish()
            }
        })

        btnBack.setOnClickListener {
            cancelServiceAndBroadcast()
            finish()
        }

        btnDisconnect.setOnClickListener {
            cancelServiceAndBroadcast()
            finish()
        }
        
        btnStreamMicToggle.setOnClickListener {
            isMicOn = !isMicOn
            btnStreamMicToggle.setImageResource(if (isMicOn) R.drawable.ic_mic_on else R.drawable.ic_mic_off)
            tvStreamMicLabel.text = if (isMicOn) "Mic On" else "Mic Off"
            
            Thread {
                try {
                    val requestUrl = java.net.URL("$baseUrl/toggleMic")
                    val connection = requestUrl.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.responseCode
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }
        
        btnStreamFlashToggle.setOnClickListener {
            isFlashOn = !isFlashOn
            btnStreamFlashToggle.setImageResource(if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
            tvStreamFlashLabel.text = if (isFlashOn) "Flash On" else "Flash Off"
            
            Thread {
                try {
                    val requestUrl = java.net.URL("$baseUrl/toggleFlash")
                    val connection = requestUrl.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.responseCode
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }

        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                showError()
            }
            
            private fun showError() {
                webView.visibility = android.view.View.GONE
                layoutError.visibility = android.view.View.VISIBLE
                btnDisconnect.visibility = android.view.View.GONE
            }
        }
        
        // Use HTML wrapper to force proper scaling (Center Crop behavior)
        val html = """
            <html>
                <body style="margin:0;padding:0;overflow:hidden;background:black;">
                    <img src="$url" style="width:100%;height:100%;object-fit:fill;" />
                </body>
            </html>
        """.trimIndent()
        
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        startAudioStream(url)
        startStatusPolling(baseUrl, btnStreamMicToggle, tvStreamMicLabel, btnStreamFlashToggle, tvStreamFlashLabel, tvBatteryLabel, tvAlertBadge)
    }




    override fun onResume() {
        super.onResume()
        startService(android.content.Intent(this, ParentMarkerService::class.java))
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.d("BabyMonitor", "StreamActivity: onStop")
        cancelServiceAndBroadcast()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelServiceAndBroadcast()
        isRunning = false
        stopAudioStream()
        stopStatusPolling()
    }

    private fun cancelServiceAndBroadcast() {
        stopService(android.content.Intent(this, ParentMarkerService::class.java))
        sendBroadcast(android.content.Intent("com.example.babymonitor.ACTION_REFRESH_UI"))
    }

    // Status Polling Logic
    private var statusHandler: android.os.Handler? = null
    private var statusRunnable: Runnable? = null
    private var isMicOn = true
    private var isFlashOn = false
    private var lastAlertState = "" // Track state to trigger new vibrations on change

    private fun startStatusPolling(
        baseUrl: String,
        btnMic: com.google.android.material.floatingactionbutton.FloatingActionButton,
        tvMic: android.widget.TextView,
        btnFlash: com.google.android.material.floatingactionbutton.FloatingActionButton,
        tvFlash: android.widget.TextView,
        tvBattery: android.widget.TextView,
        tvAlertBadge: android.widget.TextView
    ) {
        statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
        statusRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                Thread {
                    try {
                        val requestUrl = java.net.URL("$baseUrl/status")
                        val connection = requestUrl.openConnection() as java.net.HttpURLConnection
                        connection.requestMethod = "GET"
                        if (connection.responseCode == 200) {
                            val response = connection.inputStream.bufferedReader().use { it.readText() }
                            val serverMicOn = response.contains("isMicOn=true")
                            val serverFlashOn = response.contains("isFlashOn=true")
                            
                            val batteryMatch = Regex("batteryLevel=(\\d+)").find(response)
                            val serverBatteryLevel = batteryMatch?.groupValues?.get(1) ?: "..."
                            
                                runOnUiThread {
                                    if (isMicOn != serverMicOn) {
                                        isMicOn = serverMicOn
                                        btnMic.setImageResource(if (isMicOn) R.drawable.ic_mic_on else R.drawable.ic_mic_off)
                                        tvMic.text = if (isMicOn) "Mic On" else "Mic Off"
                                    }
                                    if (isFlashOn != serverFlashOn) {
                                        isFlashOn = serverFlashOn
                                        btnFlash.setImageResource(if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
                                        tvFlash.text = if (isFlashOn) "Flash On" else "Flash Off"
                                    }
                                    
                                    val isMotionActive = response.contains("isMotionActive=true")
                                    val isNoiseActive = response.contains("isNoiseActive=true")
                                    val currentAlertState = "M:$isMotionActive, N:$isNoiseActive"
                                    
                                    if (isMotionActive || isNoiseActive) {
                                        tvAlertBadge.visibility = android.view.View.VISIBLE
                                        tvAlertBadge.text = if (isMotionActive && isNoiseActive) "MOTION & NOISE DETECTED" 
                                                           else if (isMotionActive) "MOTION DETECTED" 
                                                           else "NOISE DETECTED"
                                        
                                        // Vibrate on NEW alert or alert TYPE change
                                        if (currentAlertState != lastAlertState) {
                                            lastAlertState = currentAlertState
                                            android.util.Log.d("BabyMonitor", "vibrating for alert")
                                            
                                            val vibrator = getSystemService(android.os.Vibrator::class.java)
                                            if (vibrator != null && vibrator.hasVibrator()) {
                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                    // High-visibility pulse that works on almost any O+ device
                                                    val effect = android.os.VibrationEffect.createWaveform(longArrayOf(0, 250, 100, 250), -1)
                                                    val attributes = android.media.AudioAttributes.Builder()
                                                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                                                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                                        .build()
                                                    vibrator.vibrate(effect, attributes)
                                                } else {
                                                    @Suppress("DEPRECATION")
                                                    vibrator.vibrate(longArrayOf(0, 250, 100, 250), -1)
                                                }
                                            }
                                        }
                                    } else {
                                        tvAlertBadge.visibility = android.view.View.GONE
                                        lastAlertState = currentAlertState
                                    }
                                    
                                    tvBattery.text = "🔋 Baby: $serverBatteryLevel%"
                                }
                        }
                    } catch (e: Exception) {
                        // ignore silently
                    }
                }.start()
                statusHandler?.postDelayed(this, 1500)
            }
        }
        statusHandler?.postDelayed(statusRunnable!!, 1000)
    }

    private fun stopStatusPolling() {
        statusRunnable?.let { statusHandler?.removeCallbacks(it) }
        statusHandler = null
        statusRunnable = null
    }

    // Audio Client Logic
    private var isAudioPlaying = false
    private var audioThread: Thread? = null

    private fun startAudioStream(url: String) {
        val ip = try {
            val uri = java.net.URI(url)
            uri.host
        } catch (e: Exception) {
            return
        }

        if (isAudioPlaying) return
        isAudioPlaying = true

        audioThread = Thread {
            var socket: java.net.Socket? = null
            var audioTrack: android.media.AudioTrack? = null

            try {
                // Connect to Audio Server
                socket = java.net.Socket(ip, 8081)
                socket.tcpNoDelay = true // Disable Nagle's algorithm for lower latency
                val inputStream = socket.getInputStream()
                val sampleRate = 16000
                val channelConfig = android.media.AudioFormat.CHANNEL_OUT_MONO
                val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
                val minBufSize = android.media.AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                // Optimization: Use the absolute minimum buffer size for lowest latency
                val optimizedBufSize = minBufSize

                // Use modern AudioTrack.Builder for low latency
                audioTrack = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    android.media.AudioTrack.Builder()
                        .setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            android.media.AudioFormat.Builder()
                                .setEncoding(audioFormat)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build()
                        )
                        .setBufferSizeInBytes(optimizedBufSize)
                        .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    android.media.AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        optimizedBufSize,
                        android.media.AudioTrack.MODE_STREAM
                    )
                }

                audioTrack.play()
                val buffer = ByteArray(minBufSize)

                while (isAudioPlaying && !Thread.currentThread().isInterrupted) {
                    // Check if bytes available in socket is huge (audio is lagging behind in the buffer)
                    val available = inputStream.available()
                    // 16000 Hz * 2 bytes = 32000 bytes/sec. If we have more than 1/4 second backed up (8000 bytes),
                    // skip/drain some of the buffer to catch up to live time.
                    if (available > 8000) {
                        val drainAmount = available - 4000
                        var skip = 0
                        while (skip < drainAmount) {
                            val skipped = inputStream.read(buffer, 0, Math.min(buffer.size, drainAmount - skip))
                            if (skipped <= 0) break
                            skip += skipped
                        }
                    }

                    val read = inputStream.read(buffer, 0, minBufSize)
                    if (read > 0) {
                        audioTrack.write(buffer, 0, read)
                    } else {
                        break // Disconnected
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                    socket?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        audioThread?.start()
    }

    private fun stopAudioStream() {
        isAudioPlaying = false
        audioThread?.interrupt()
        audioThread = null
    }
    
    private fun <T> getServiceSafe(serviceClass: Class<T>): T? {
        return try {
            getSystemService(serviceClass)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        var isRunning = false
    }
}
