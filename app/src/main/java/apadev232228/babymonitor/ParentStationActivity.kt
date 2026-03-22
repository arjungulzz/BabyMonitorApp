package apadev232228.babymonitor

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
import androidx.core.content.ContextCompat
import java.net.InetAddress

class ParentStationActivity : AppCompatActivity() {

    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val services = mutableListOf<NsdServiceInfo>()
    private lateinit var adapter: ServerAdapter

    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var statusIndicator: View
    private val handler = Handler(Looper.getMainLooper())
    private var isDiscovering = false

    private lateinit var recyclerView: RecyclerView
    private val foundServices = mutableListOf<NsdServiceInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_station)
        
        // Handle edge-to-edge display
        setupWindowInsets()

        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        statusIndicator = findViewById(R.id.statusIndicator)
        
        // Setup pull-to-refresh
        swipeRefresh.setColorSchemeColors(
            androidx.core.content.ContextCompat.getColor(this, R.color.primary_blue),
            androidx.core.content.ContextCompat.getColor(this, R.color.accent_coral)
        )
        
        // Set progress view offset to avoid camera hinge
        swipeRefresh.setProgressViewOffset(false, 150, 300)
        
        swipeRefresh.setOnRefreshListener {
            // Haptic feedback
            swipeRefresh.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            startDiscovery()
        }

        setupRecyclerView()
        setupAds()
    }

    private fun setupAds() {
        val adView = findViewById<com.google.android.gms.ads.AdView>(R.id.adView)
        
        // Handle window insets for navigation bars
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(adView) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val layoutParams = view.layoutParams as android.widget.FrameLayout.LayoutParams
            layoutParams.bottomMargin = systemBars.bottom + resources.getDimensionPixelSize(R.dimen.spacing_md)
            view.layoutParams = layoutParams
            insets
        }
        
        if (apadev232228.babymonitor.billing.BillingManager.isProUser(this)) {
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
        
        // Add item animator for smooth animations
        recyclerView.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 300
            removeDuration = 300
        }
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
        val host = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.hostAddresses.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            service.host
        }
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
            val hostAddr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.hostAddresses.firstOrNull()?.hostAddress ?: "Unknown"
            } else {
                @Suppress("DEPRECATION")
                service.host?.hostAddress ?: "Unknown"
            }
            holder.tvIp.text = "$hostAddr:${service.port}"
            
            // Scale animation on appear
            holder.itemView.alpha = 0f
            holder.itemView.scaleX = 0.9f
            holder.itemView.scaleY = 0.9f
            holder.itemView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setStartDelay((position * 50).toLong())
                .start()
            
            holder.btnConnect.setOnClickListener {
                // Haptic feedback
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick(service)
            }
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
        swipeRefresh.isRefreshing = true
        startPulsingIndicator()
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
            swipeRefresh.isRefreshing = false
            stopPulsingIndicator()
            
            // Update main list from temp list
            services.addAll(foundServices)
            adapter.notifyDataSetChanged()
            
            if (services.isEmpty()) {
                tvStatus.text = "No Baby Monitors found. Pull to refresh."
                recyclerView.visibility = View.GONE
                findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
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
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Resolve Succeeded. $serviceInfo")
                        runOnUiThread {
                            if (foundServices.none { it.serviceName == serviceInfo.serviceName }) {
                                foundServices.add(serviceInfo)
                                tvStatus.text = "Searching... (Found ${foundServices.size})"
                            }
                        }
                    }
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    nsdManager.registerServiceInfoCallback(service, ContextCompat.getMainExecutor(this@ParentStationActivity), object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                             Log.e(TAG, "Callback registration failed: $errorCode")
                        }
                        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                            Log.d(TAG, "Service Updated/Resolved. $serviceInfo")
                            runOnUiThread {
                                if (foundServices.none { it.serviceName == serviceInfo.serviceName }) {
                                    foundServices.add(serviceInfo)
                                    tvStatus.text = "Searching... (Found ${foundServices.size})"
                                }
                            }
                        }
                        override fun onServiceLost() {
                            Log.d(TAG, "Service lost in callback")
                        }
                        override fun onServiceInfoCallbackUnregistered() {}
                    })
                } else {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(service, resolveListener)
                }
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
    
    private fun startPulsingIndicator() {
        statusIndicator.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .alpha(0.5f)
            .setDuration(600)
            .withEndAction {
                if (isDiscovering) {
                    statusIndicator.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(600)
                        .withEndAction {
                            if (isDiscovering) startPulsingIndicator()
                        }
                        .start()
                }
            }
            .start()
    }
    
    private fun stopPulsingIndicator() {
        statusIndicator.clearAnimation()
        statusIndicator.scaleX = 1f
        statusIndicator.scaleY = 1f
        statusIndicator.alpha = 1f
    }
    
    private fun setupWindowInsets() {
        val headerGradient = findViewById<View>(R.id.headerGradient)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(headerGradient) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.layoutParams.height = 160 + systemBars.top
            view.requestLayout()
            insets
        }
    }

    companion object {
        private const val TAG = "ParentStationActivity"
    }


}
