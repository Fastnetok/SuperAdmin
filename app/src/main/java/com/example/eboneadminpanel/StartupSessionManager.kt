package com.example.superadmin

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.superadmin.core.CompanyDataHelper
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Handles background session "pokes" on app startup.
 * Staggers enabled panels for active company to ensure cookies are fresh.
 */
object StartupSessionManager {
    private const val TAG = "StartupSession"
    private const val NOTIF_ID = 8001
    private const val CHANNEL_ID = "startup_sync"

    fun startRefresh(context: Context) {
        Log.d(TAG, "Starting staggered session refresh...")
        val currentCompany = CompanyDataHelper.getCurrentCompanyId(context) ?: "OKARA"
        val canonicalDocId = CompanyDataHelper.getCanonicalDocId(currentCompany)

        val db = FirebaseFirestore.getInstance()
        db.collection("franchiseSettings").document(canonicalDocId).get().addOnSuccessListener { snapshot ->
            val enabledCompanies = snapshot.get("enabledCompanies") as? Map<*, *>
            val eboneEnabled = enabledCompanies?.get("EBONE") as? Boolean ?: true
            val wateenEnabled = enabledCompanies?.get("WATEEN") as? Boolean ?: false
            val zongEnabled = enabledCompanies?.get("ZONG") as? Boolean ?: false

            val tasks = mutableListOf<Pair<String, String>>()
            if (eboneEnabled) tasks.add("EBONE" to currentCompany)
            if (wateenEnabled) tasks.add("WATEEN" to currentCompany)
            if (zongEnabled) {
                tasks.add("ZONG" to currentCompany)
                if (canonicalDocId.equals("RENALA", true) || currentCompany.equals("RENALA", true)) {
                    tasks.add("ZONG" to "Renala")
                }
            }

            if (tasks.isEmpty()) {
                Log.d(TAG, "No ISP enabled for company $currentCompany")
                return@addOnSuccessListener
            }

            showProgressNotification(context, "Updating Panel Sessions...", "${tasks[0].first} ($currentCompany) ریفریش ہو رہا ہے...")
            val handler = Handler(Looper.getMainLooper())

            tasks.forEachIndexed { index, (isp, zone) ->
                val delay = (index * 5000).toLong()
                handler.postDelayed({
                    updateNotification(context, "Updating Panel Sessions...", "$isp ($zone) ریفریش ہو رہا ہے...")
                    BackgroundBalanceUpdater.checkBalance(context, isp, zone) {
                        Log.d(TAG, "$isp ($zone) poke done")
                        if (index == tasks.size - 1) {
                            cancelNotification(context)
                        }
                    }
                }, delay)
            }
        }.addOnFailureListener {
            BackgroundBalanceUpdater.checkBalance(context, "EBONE", currentCompany) {
                cancelNotification(context)
            }
        }
    }

    private fun showProgressNotification(context: Context, title: String, msg: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Startup Sync", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(msg)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            manager.notify(NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing startup sync notification: ${e.message}")
        }
    }

    private fun updateNotification(context: Context, title: String, msg: String) {
        showProgressNotification(context, title, msg)
    }

    private fun cancelNotification(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIF_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling startup sync notification: ${e.message}")
        }
    }
}
