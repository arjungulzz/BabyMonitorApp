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
        startDiscovery()
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ServerAdapter(services) { service ->
            val intent = Intent(this, StreamActivity::class.java)
            val host = service.host
            val port = service.port
            if (host != null) {
                val url = "http://${host.hostAddress}:$port"
                intent.putExtra("STREAM_URL", url)
                startActivity(intent)
            }
        }
        recyclerView.adapter = adapter
    }

    private fun startDiscovery() {
        if (isDiscovering) return

        services.clear()
        adapter.notifyDataSetChanged()
        
        tvStatus.text = "Searching for Baby Monitors..."
        progressBar.visibility = View.VISIBLE
        btnRefresh.isEnabled = false
        
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener = createDiscoveryListener()
        
        try {
            nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            isDiscovering = true
            
            // Stop after 5 seconds
            handler.postDelayed({
                stopDiscovery()
            }, 5000)
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
            if (services.isEmpty()) {
                tvStatus.text = "No Baby Monitors found. Please check connection and try again."
            } else {
                tvStatus.text = "Found ${services.size} monitor(s)."
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
                            if (services.none { it.serviceName == serviceInfo.serviceName }) {
                                services.add(serviceInfo)
                                adapter.notifyDataSetChanged()
                                tvStatus.text = "Found: ${serviceInfo.serviceName}..."
                            }
                        }
                    }
                })
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e(TAG, "service lost: $service")
            runOnUiThread {
                services.removeAll { it.serviceName == service.serviceName }
                adapter.notifyDataSetChanged()
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

    class ServerAdapter(
        private val services: List<NsdServiceInfo>,
        private val onClick: (NsdServiceInfo) -> Unit
    ) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvServerName)
            val tvIp: TextView = view.findViewById(R.id.tvServerIp)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_server, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val service = services[position]
            holder.tvName.text = service.serviceName
            val host = service.host?.hostAddress ?: "Unknown"
            holder.tvIp.text = "$host:${service.port}"
            holder.itemView.setOnClickListener { onClick(service) }
        }

        override fun getItemCount() = services.size
    }
}
