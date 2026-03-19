package com.example.babymonitor.utils

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.babymonitor.R
import com.example.babymonitor.billing.BillingManager

object DialogUtils {

    fun showProUpgradeDialog(context: Context, onSuccess: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Upgrade to Pro 👑")
            .setIcon(R.drawable.ic_crown) // Ensure this drawable exists or use a generic one
            .setMessage(
                "Unlock the full power of your Baby Monitor:\n\n" +
                "🚫 **Remove All Ads**\n" +
                "🔔 **Motion & Noise Alerts**\n" +
                "📏 **Custom Motion Zones**\n\n" +
                "Support development for a small one-time fee!\n\n" +
                "⚠️ *Note: Please purchase Pro on the specific device/Google account you plan to use as the Baby Station.*"
            )
            .setPositiveButton("Go Pro (${BillingManager.getProductPrice() ?: "Check Price"})") { _, _ ->
                BillingManager.purchasePro(context as android.app.Activity) {
                    onSuccess()
                }
            }
            .setNegativeButton("Restore Purchase") { dialog, _ ->
                 Toast.makeText(context, "Checking for purchases...", Toast.LENGTH_SHORT).show()
                 BillingManager.verifyProStatus(context) { isPro ->
                     (context as? android.app.Activity)?.runOnUiThread {
                         if (isPro) {
                             Toast.makeText(context, "Purchase successfully restored! 👑", Toast.LENGTH_LONG).show()
                             onSuccess()
                         } else {
                             Toast.makeText(context, "No existing purchase found for this account.", Toast.LENGTH_LONG).show()
                         }
                     }
                 }
            }
            .setNeutralButton("Cancel", null)
            .create()
            .show()
    }
}
