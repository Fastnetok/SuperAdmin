package com.example.superadmin.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppActivityTracker {

    private const val TAG = "AppActivityTracker"
    private const val HEARTBEAT_INTERVAL_MS = 30_000L

    private var db: FirebaseDatabase? = null
    private var isInitialized = false

    private var activeActivityCount = 0
    private var sessionStartTime = 0L
    private var foregroundStartTime = 0L
    private var currentDailyUsageMins = 0L

    private var cachedEmail: String? = null
    private var cachedName: String? = null
    private var cachedRole: String? = null
    private var cachedCompanyId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (activeActivityCount > 0) {
                sendHeartbeat()
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun init(app: Application) {
        if (isInitialized) return
        isInitialized = true

        try {
            db = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FirebaseDatabase: ${e.message}")
        }

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val wasBackground = (activeActivityCount == 0)
                activeActivityCount++

                if (wasBackground) {
                    onAppForegrounded(activity.javaClass.simpleName)
                } else {
                    // Screen change action
                    logAction(activity.javaClass.simpleName, "Navigated to ${getReadableScreenName(activity.javaClass.simpleName)}")
                }
            }

            override fun onActivityPaused(activity: Activity) {
                activeActivityCount = (activeActivityCount - 1).coerceAtLeast(0)
                if (activeActivityCount == 0) {
                    onAppBackgrounded()
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            val user = auth.currentUser
            if (user != null) {
                updateUserCache(user.email)
            } else {
                clearCache()
            }
        }
    }

    private fun sanitizeKey(key: String): String {
        return key.trim().lowercase()
            .replace(".", "_")
            .replace("#", "_")
            .replace("$", "_")
            .replace("[", "_")
            .replace("]", "_")
    }

    private fun updateUserCache(email: String?) {
        val safeEmail = email?.trim()?.lowercase()
        if (safeEmail.isNullOrEmpty()) return
        cachedEmail = safeEmail
        val sanitizedEmail = sanitizeKey(safeEmail)

        FirebaseFirestore.getInstance().collection("admins")
            .whereEqualTo("email", safeEmail)
            .get()
            .addOnSuccessListener { snapshot ->
                val doc = snapshot.documents.firstOrNull()
                if (doc != null) {
                    cachedName = doc.getString("name") ?: cachedEmail?.substringBefore("@")?.replaceFirstChar { it.uppercase() }
                    cachedRole = doc.getString("role") ?: "COMPANY_ADMIN"
                    cachedCompanyId = doc.getString("companyId")?.trim()?.uppercase() ?: "OKARA"
                } else {
                    // Check fallback in companies collection
                    FirebaseFirestore.getInstance().collection("companies")
                        .whereEqualTo("adminEmail", cachedEmail)
                        .get()
                        .addOnSuccessListener { compSnap ->
                            val compDoc = compSnap.documents.firstOrNull()
                            if (compDoc != null) {
                                cachedName = compDoc.getString("ownerName") ?: compDoc.getString("companyName")
                                cachedRole = "COMPANY_ADMIN"
                                cachedCompanyId = compDoc.getString("companyId")?.trim()?.uppercase() ?: compDoc.id.trim().uppercase()
                            } else {
                                cachedName = cachedEmail?.substringBefore("@")?.replaceFirstChar { it.uppercase() } ?: "Admin"
                                cachedRole = if (cachedEmail == "abbasok@gmail.com") "SUPERADMIN" else "COMPANY_ADMIN"
                                cachedCompanyId = "OKARA"
                            }
                        }
                }
            }
    }

    private fun String?.isNull_or_empty(): Boolean = this == null || this.trim().isEmpty()

    private fun getPresenceRef(): DatabaseReference? {
        val email = cachedEmail ?: FirebaseAuth.getInstance().currentUser?.email ?: return null
        val key = sanitizeKey(email)
        return db?.getReference("user_presence")?.child(key)
    }

    private fun onAppForegrounded(screenName: String) {
        foregroundStartTime = System.currentTimeMillis()
        if (sessionStartTime == 0L) {
            sessionStartTime = foregroundStartTime
        }

        val ref = getPresenceRef() ?: return
        val now = System.currentTimeMillis()
        val todayStr = dateFormat.format(Date(now))

        val email = cachedEmail ?: FirebaseAuth.getInstance().currentUser?.email ?: "admin@system.com"
        val name = cachedName ?: email.substringBefore("@").replaceFirstChar { it.uppercase() }
        val role = cachedRole ?: "COMPANY_ADMIN"
        val companyId = cachedCompanyId ?: "OKARA"

        ref.get().addOnSuccessListener { snapshot ->
            val savedToday = snapshot.child("todayDate").value?.toString() ?: ""
            currentDailyUsageMins = if (savedToday == todayStr) {
                snapshot.child("dailyUsageMinutes").getValue(Long::class.java) ?: 0L
            } else {
                0L
            }

            val updates = mapOf<String, Any>(
                "email" to email,
                "name" to name,
                "role" to role,
                "companyId" to companyId,
                "isOnline" to true,
                "lastSeen" to ServerValue.TIMESTAMP,
                "sessionStart" to if (sessionStartTime > 0) sessionStartTime else now,
                "dailyUsageMinutes" to currentDailyUsageMins,
                "todayDate" to todayStr
            )

            ref.updateChildren(updates)
            ref.onDisconnect().updateChildren(
                mapOf(
                    "isOnline" to false,
                    "lastSeen" to ServerValue.TIMESTAMP,
                    "lastLogout" to ServerValue.TIMESTAMP
                )
            )

            logAction(screenName, "Opened ${getReadableScreenName(screenName)}")
            handler.removeCallbacks(heartbeatRunnable)
            handler.post(heartbeatRunnable)
        }
    }

    private fun onAppBackgrounded() {
        handler.removeCallbacks(heartbeatRunnable)
        val ref = getPresenceRef() ?: return
        val now = System.currentTimeMillis()

        if (foregroundStartTime > 0) {
            val sessionDeltaMins = ((now - foregroundStartTime) / (60 * 1000L)).coerceAtLeast(0)
            currentDailyUsageMins += sessionDeltaMins
        }

        val updates = mapOf<String, Any>(
            "isOnline" to false,
            "lastSeen" to now,
            "lastLogout" to now,
            "dailyUsageMinutes" to currentDailyUsageMins
        )

        ref.updateChildren(updates)
    }

    private fun sendHeartbeat() {
        val ref = getPresenceRef() ?: return
        ref.child("lastSeen").setValue(ServerValue.TIMESTAMP)
        ref.child("isOnline").setValue(true)
    }

    fun onUserLogout() {
        val ref = getPresenceRef()
        val now = System.currentTimeMillis()
        ref?.updateChildren(
            mapOf(
                "isOnline" to false,
                "lastSeen" to now,
                "lastLogout" to now
            )
        )
        sessionStartTime = 0L
        foregroundStartTime = 0L
        clearCache()
    }

    private fun clearCache() {
        cachedEmail = null
        cachedName = null
        cachedRole = null
        cachedCompanyId = null
    }

    fun logAction(
        actionTag: String,
        actionDetails: String,
        companyIdOverride: String? = null
    ) {
        val email = cachedEmail ?: FirebaseAuth.getInstance().currentUser?.email ?: return
        val sanitizedEmail = sanitizeKey(email)
        val name = cachedName ?: email.substringBefore("@").replaceFirstChar { it.uppercase() }
        val role = cachedRole ?: "COMPANY_ADMIN"
        val companyId = companyIdOverride?.trim()?.uppercase() ?: cachedCompanyId ?: "OKARA"

        val now = System.currentTimeMillis()
        val dateStr = dateFormat.format(Date(now))
        val logsRef = db?.getReference("activity_logs") ?: return
        val logId = logsRef.push().key ?: return

        val logData = mapOf<String, Any>(
            "logId" to logId,
            "email" to email,
            "sanitizedEmail" to sanitizedEmail,
            "name" to name,
            "role" to role,
            "companyId" to companyId,
            "action" to actionTag,
            "details" to actionDetails,
            "timestamp" to now,
            "date" to dateStr
        )

        logsRef.child(logId).setValue(logData)
    }

    private fun getReadableScreenName(className: String): String {
        return when (className) {
            "MenuActivity" -> "SuperAdmin Menu"
            "CompanyLogActivity" -> "Company Log & Analytics"
            "CompanyManagerActivity" -> "Company Management"
            "CityFranchiseManagementActivity" -> "City & Franchise Manager"
            "AddComplaintActivity" -> "New Complaint Screen"
            "AllComplaintsActivity" -> "All Complaints"
            "AssignComplaintsActivity" -> "Assign Complaints"
            "BiometricAttendanceActivity" -> "Biometric Attendance"
            "OfficeTimmingsActivity" -> "Office Timings"
            "AddEmployeeActivity" -> "Admin & Employee Management"
            "AllEmployeesActivity" -> "All Employees List"
            "ReportsActivity" -> "Reports Dashboard"
            "MovementTrackingActivity" -> "Movement Tracking"
            "CustomerBillingActivity" -> "Customer Billing"
            "AddCustomerActivity", "Addcustomeractivity" -> "Add Customer Screen"
            "IspPanelSettingsActivity" -> "ISP Panel Settings"
            "SmsMatchSettingsActivity" -> "SMS Match Settings"
            "DealerDashboardActivity" -> "Dealer Dashboard"
            "AutoComplaintMonitorActivity" -> "Auto Complaint Monitor"
            "LoginActivity" -> "Login Screen"
            else -> className.replace("Activity", "")
        }
    }
}
