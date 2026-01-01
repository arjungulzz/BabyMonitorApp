package com.example.babymonitor.billing

import android.content.Context
import android.content.SharedPreferences

object BillingManager {
    private const val PREFS_NAME = "billing_prefs"
    private const val KEY_IS_PRO = "is_pro_user"

    fun isProUser(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_PRO, false)
    }

    fun purchasePro(context: Context, onSuccess: () -> Unit) {
        // Mock Purchase Flow: Just set the flag after a delay
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_IS_PRO, true).apply()
            onSuccess()
        }, 1500) // 1.5 second simulated delay
    }
}
