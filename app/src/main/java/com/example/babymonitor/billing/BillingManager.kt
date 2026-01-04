package com.example.babymonitor.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.android.billingclient.api.*

object BillingManager {
    private const val PREFS_NAME = "billing_prefs"
    private const val KEY_IS_PRO = "is_pro_user"
    private const val PRO_LIFETIME_SKU = "pro_lifetime"
    private const val TAG = "BillingManager"
    
    // Set to TRUE to simulate purchases without a Google Play Developer Account
    // Set to FALSE when ready to test real billing with a signed APK
    private const val USE_MOCK_BILLING = true 

    private var billingClient: BillingClient? = null
    private var productDetailsMap = mutableMapOf<String, ProductDetails>()

    fun getProductPrice(sku: String = PRO_LIFETIME_SKU): String? {
        val productDetails = productDetailsMap[sku]
        return productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
            ?: if (USE_MOCK_BILLING) "$4.99 (Mock)" else null
    }

    fun isProUser(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_PRO, false)
    }

    fun purchasePro(activity: Activity, onSuccess: () -> Unit) {
        if (USE_MOCK_BILLING) {
            simulatePurchase(activity, onSuccess)
        } else {
            initBillingClient(activity) {
                launchBillingFlow(activity, PRO_LIFETIME_SKU, onSuccess)
            }
        }
    }

    private fun simulatePurchase(context: Context, onSuccess: () -> Unit) {
        // Mock Purchase Flow
        Toast.makeText(context, "Mocking Purchase...", Toast.LENGTH_SHORT).show()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            setProStatus(context, true)
            onSuccess()
        }, 1500)
    }

    private fun setProStatus(context: Context, isPro: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_PRO, isPro).apply()
    }

    fun verifyProStatus(context: Context) {
        if (USE_MOCK_BILLING) return // No cloud to sync with in mock mode

        val client = BillingClient.newBuilder(context)
            .setListener { _, _ -> } // No UI listener needed for background check
            .enablePendingPurchases()
            .build()

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val params = QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                    
                    client.queryPurchasesAsync(params) { result, purchases ->
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            var foundPro = false
                            for (p in purchases) {
                                if (p.products.contains(PRO_LIFETIME_SKU) && p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                                    foundPro = true
                                    break
                                }
                            }
                            // Only update if true (don't revoke if offline/error)
                            if (foundPro) {
                                setProStatus(context, true)
                                Log.d(TAG, "Pro Status Verified & Restored")
                            }
                        }
                        client.endConnection()
                    }
                } else {
                    client.endConnection()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Do nothing, just a background check
            }
        })
    }

    // Existing Purchase Logic (kept for 'Go Pro' button)
    private fun initBillingClient(context: Context, onReady: () -> Unit) {
        if (billingClient != null) {
            onReady()
            return
        }

        billingClient = BillingClient.newBuilder(context)
            .setListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    for (purchase in purchases) {
                        handlePurchase(context, purchase)
                    }
                } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                    Log.d(TAG, "User canceled purchase")
                } else {
                    Log.e(TAG, "Purchase failed: ${billingResult.responseCode}")
                }
            }
            .enablePendingPurchases()
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing Setup Finished")
                    queryProductDetails()
                    // Check for existing purchases during interactive setup too
                    checkExistingPurchasesForInteractiveFlow()
                    onReady()
                } else {
                    Log.e(TAG, "Billing Setup Failed: ${billingResult.debugMessage}")
                    Toast.makeText(context, "Billing Setup Failed: ${billingResult.responseCode}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.e(TAG, "Billing Service Disconnected")
            }
        })
    }
    
    // Helper to check purchases during interactive flow
    private fun checkExistingPurchasesForInteractiveFlow() {
       val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient?.queryPurchasesAsync(params) { result, purchases ->
             if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                 for (p in purchases) {
                     if (p.products.contains(PRO_LIFETIME_SKU) && p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                         // silently acknowledge/update if found
                     }
                 }
             }
        }
    }

    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRO_LIFETIME_SKU)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient?.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (productDetails in productDetailsList) {
                    productDetailsMap[productDetails.productId] = productDetails
                }
            }
        }
    }

    private fun launchBillingFlow(activity: Activity, skuId: String, onSuccess: () -> Unit) {
        val productDetails = productDetailsMap[skuId]
        if (productDetails != null) {
            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .build()
            )

            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()

            billingClient?.launchBillingFlow(activity, billingFlowParams)
        } else {
             Toast.makeText(activity, "Product not found (Are you published?)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePurchase(context: Context, purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient?.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        setProStatus(context, true)
                        Toast.makeText(context, "Purchase Successful! \uD83D\uDC51", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                 setProStatus(context, true)
                 Toast.makeText(context, "Purchase Restored! \uD83D\uDC51", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
