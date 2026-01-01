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
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val currentFrame = AtomicReference<ByteArray>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_baby_station)

        startCamera()
        startServer()
        registerService()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            val viewFinder = findViewById<PreviewView>(R.id.viewFinder)
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
                yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 60, out)
                val bytes = out.toByteArray()

                if (rotationDegrees != 0) {
                     val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                     val matrix = android.graphics.Matrix()
                     matrix.postRotate(rotationDegrees.toFloat())
                     val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                     val out2 = ByteArrayOutputStream()
                     rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, out2)
                     currentFrame.set(out2.toByteArray())
                } else {
                     currentFrame.set(bytes)
                }
                
                imageProxy.close()
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
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
        }
    }

    private fun registerService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "BabyMonitor-${System.currentTimeMillis()}"
            serviceType = "_http._tcp."
            port = 8080
        }

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
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
        nsdManager?.unregisterService(registrationListener)
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
        private const val TAG = "BabyStationActivity"
    }
}
