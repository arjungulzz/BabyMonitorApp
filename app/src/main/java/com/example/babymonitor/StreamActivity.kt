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

        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                showError()
            }

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
    }

    private fun cancelServiceAndBroadcast() {
        stopService(android.content.Intent(this, ParentMarkerService::class.java))
        sendBroadcast(android.content.Intent("com.example.babymonitor.ACTION_REFRESH_UI"))
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

                val sampleRate = 44100
                val channelConfig = android.media.AudioFormat.CHANNEL_OUT_MONO
                val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
                val minBufSize = android.media.AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                // Optimization: Use a smaller buffer size for lower latency if safe
                val optimizedBufSize = if (minBufSize > 4096) minBufSize / 2 else minBufSize

                audioTrack = android.media.AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    optimizedBufSize, // Reduced buffer
                    android.media.AudioTrack.MODE_STREAM
                )

                audioTrack.play()
                val buffer = ByteArray(minBufSize)

                while (isAudioPlaying && !Thread.currentThread().isInterrupted) {
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
    
    companion object {
        var isRunning = false
    }
}
