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
        val layoutError = findViewById<android.view.View>(R.id.layoutError)
        val btnBack = findViewById<android.view.View>(R.id.btnBack)
        val btnDisconnect = findViewById<android.view.View>(R.id.btnDisconnect)

        btnBack.setOnClickListener {
            finish()
        }

        btnDisconnect.setOnClickListener {
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
        
        webView.loadUrl(url)
    }
}
