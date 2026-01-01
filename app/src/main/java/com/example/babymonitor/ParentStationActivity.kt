package com.example.babymonitor

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.InetAddress

class ParentStationActivity : AppCompatActivity() {

    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val services = mutableListOf<NsdServiceInfo>()
    private lateinit var adapter: ServerAdapter

    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRefresh: Button
    private val handler = Handler(Looper.getMainLooper())
    private var isDiscovering = false

    private lateinit var recyclerView: RecyclerView
    private val foundServices = mutableListOf<NsdServiceInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_station)

        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnRefresh.setOnClickListener {
            startDiscovery()
        }

        setupRecyclerView()
        setupAds()
    }

    private fun setupAds() {
        val adView = findViewById<com.google.android.gms.ads.AdView>(R.id.adView)
        if (com.example.babymonitor.billing.BillingManager.isProUser(this)) {
            adView.visibility = View.GONE
        } else {
            val adRequest = com.google.android.gms.ads.AdRequest.Builder().build()
            adView.loadAd(adRequest)
        }
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ServerAdapter(services) { service ->
            showConnectingDialog(service)
        }
        recyclerView.adapter = adapter
    }

    private fun showConnectingDialog(service: NsdServiceInfo) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_connecting, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBarConnecting)
        val tvConnecting = dialogView.findViewById<TextView>(R.id.tvConnecting)
        
        tvConnecting.text = "Connecting to ${service.serviceName}..."
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
            
        dialog.show()
        
        // Dummy progress animation
        var progress = 0
        val runnable = object : Runnable {
            override fun run() {
                if (progress >= 100) {
                    dialog.dismiss()
                    startStream(service)
                } else {
                    progress += 4 // 25 steps * 40ms = 1 second approx (actually slightly more due to processing)
                    progressBar.progress = progress
                    handler.postDelayed(this, 40)
                }
            }
        }
        handler.post(runnable)
    }

    private fun startStream(service: NsdServiceInfo) {
        val intent = Intent(this, StreamActivity::class.java)
        val host = service.host
        val port = service.port
        if (host != null) {
            val url = "http://${host.hostAddress}:$port"
            intent.putExtra("STREAM_URL", url)
            startActivity(intent)
        }
    }

    // ... (rest of discovery logic) ...

    class ServerAdapter(
        private val services: List<NsdServiceInfo>,
        private val onClick: (NsdServiceInfo) -> Unit
    ) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvServerName)
            val tvIp: TextView = view.findViewById(R.id.tvServerIp)
            val btnConnect: Button = view.findViewById(R.id.btnConnect)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_server_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val service = services[position]
            holder.tvName.text = service.serviceName
            val host = service.host?.hostAddress ?: "Unknown"
            holder.tvIp.text = "$host:${service.port}"
            
            holder.btnConnect.setOnClickListener { onClick(service) }
            // Also allow clicking the card
            holder.itemView.setOnClickListener { onClick(service) }
        }

        override fun getItemCount() = services.size
    }

    override fun onResume() {
        super.onResume()
        startDiscovery()
    }

    private fun startDiscovery() {
        if (isDiscovering) return

        // Clear UI list immediately
        services.clear()
        adapter.notifyDataSetChanged()
        
        // Use temp list for accumulation
        foundServices.clear()
        
        // Update UI state
        tvStatus.text = "Searching... (Found 0)"
        progressBar.visibility = View.VISIBLE
        btnRefresh.isEnabled = false
        recyclerView.visibility = View.GONE
        
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener = createDiscoveryListener()
        
        try {
            nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            isDiscovering = true
            
            // Stop after 2 seconds
            handler.postDelayed({
                stopDiscovery()
            }, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Start discovery failed", e)
            stopDiscovery()
        }
    }

    private fun stopDiscovery() {
        if (!isDiscovering) return
        
        try {
            if (discoveryListener != null) {
                nsdManager.stopServiceDiscovery(discoveryListener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop discovery failed", e)
        }
        
        isDiscovering = false
        runOnUiThread {
            progressBar.visibility = View.GONE
            btnRefresh.isEnabled = true
            
            // Update main list from temp list
            services.addAll(foundServices)
            adapter.notifyDataSetChanged()
            
            if (services.isEmpty()) {
                tvStatus.text = "No Baby Monitors found. Please check connection and try again."
                recyclerView.visibility = View.GONE
            } else {
                tvStatus.text = "Found ${services.size} monitor(s). Select to view."
                recyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun createDiscoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "Service discovery started")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "Service found: $service")
            if (service.serviceType.contains("_http._tcp")) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Resolve Succeeded. $serviceInfo")
                        runOnUiThread {
                            // Add to temp list instead of main list
                            if (foundServices.none { it.serviceName == serviceInfo.serviceName }) {
                                foundServices.add(serviceInfo)
                                tvStatus.text = "Searching... (Found ${foundServices.size})"
                            }
                        }
                    }
                })
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e(TAG, "service lost: $service")
            // No-op during batch scan usually, or remove from temp list
            runOnUiThread {
                 foundServices.removeAll { it.serviceName == service.serviceName }
                 // Update count if currently scanning
                 if (isDiscovering) {
                     tvStatus.text = "Searching... (Found ${foundServices.size})"
                 }
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "Discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery failed: Error code:$errorCode")
            nsdManager.stopServiceDiscovery(this)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery failed: Error code:$errorCode")
            nsdManager.stopServiceDiscovery(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
    }

    companion object {
        private const val TAG = "ParentStationActivity"
    }


}
