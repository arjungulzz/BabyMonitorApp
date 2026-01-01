package com.example.babymonitor

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class StreamActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream)

        val url = intent.getStringExtra("STREAM_URL") ?: return

        val webView = findViewById<WebView>(R.id.webView)
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.webViewClient = WebViewClient()
        webView.loadUrl(url)
    }
}
