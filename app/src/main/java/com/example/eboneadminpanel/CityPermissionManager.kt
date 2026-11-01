package com.example.superadmin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.superadmin.core.SessionManager
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore

object CityPermissionManager {

    private const val TAG = "CityPermissionDebug"
    private const val PREF_NAME = "city_permissions_cache"

    private fun getCachePrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun clearCache(context: Context) {
        getCachePrefs(context).edit().clear().apply()
        Log.d(TAG, "City permissions cache completely cleared.")
    }

    fun isCompanyEnabled(context: Context, zone: String, companyName: String, callback: (Boolean) -> Unit) {
        val sessionMgr = SessionManager(context)
        val rawZone = if (zone.isNotBlank() && zone.uppercase() != "ALL") zone else (sessionMgr.getActiveCompanyId() ?: sessionMgr.getCompanyId() ?: "OKARA")
        val targetZone = rawZone.trim().uppercase()
        val prefs = getCachePrefs(context)
        val cacheKey = "${targetZone}_company_$companyName"

        val cachedValue = if (prefs.contains(cacheKey)) {
            prefs.getBoolean(cacheKey, false)
        } else {
            false
        }
        callback(cachedValue)

        checkPermission(context, zone, companyName, true) { isEnabled ->
            if (cachedValue != isEnabled) {
                prefs.edit().putBoolean(cacheKey, isEnabled).apply()
                callback(isEnabled)
            } else {
                prefs.edit().putBoolean(cacheKey, isEnabled).apply()
            }
        }
    }

    fun isFeatureEnabled(context: Context, zone: String, featureName: String, callback: (Boolean) -> Unit) {
        val sessionMgr = SessionManager(context)
        val rawZone = if (zone.isNotBlank() && zone.uppercase() != "ALL") zone else (sessionMgr.getActiveCompanyId() ?: sessionMgr.getCompanyId() ?: "OKARA")
        val targetZone = rawZone.trim().uppercase()
        val prefs = getCachePrefs(context)
        val cacheKey = "${targetZone}_feature_$featureName"

        val defaultVal = when (featureName.trim().lowercase()) {
            "movement tracking", "auto complaint monitor", "dealer webview" -> false
            "dealer panel" -> true
            else -> true
        }

        val cachedValue = if (prefs.contains(cacheKey)) {
            prefs.getBoolean(cacheKey, defaultVal)
        } else {
            defaultVal
        }
        callback(cachedValue)

        checkPermission(context, zone, featureName, false) { isEnabled ->
            if (cachedValue != isEnabled) {
                prefs.edit().putBoolean(cacheKey, isEnabled).apply()
                callback(isEnabled)
            } else {
                prefs.edit().putBoolean(cacheKey, isEnabled).apply()
            }
        }
    }

    fun getEnabledCompanies(context: Context, zone: String, callback: (List<String>) -> Unit) {
        val sessionMgr = SessionManager(context)
        val rawZone = if (zone.isNotBlank() && zone.uppercase() != "ALL") zone else (sessionMgr.getActiveCompanyId() ?: sessionMgr.getCompanyId() ?: "OKARA")
        val targetZone = rawZone.trim().uppercase()
        val all = AppFeatures.ALL_COMPANIES
        val enabledList = mutableListOf<String>()
        var checkedCount = 0
        for (company in all) {
            isCompanyEnabled(context, targetZone, company) { isEnabled ->
                synchronized(enabledList) {
                    if (isEnabled && !enabledList.contains(company)) {
                        enabledList.add(company)
                    }
                    checkedCount++
                    if (checkedCount == all.size) {
                        val sorted = all.filter { enabledList.contains(it) }
                        callback(sorted)
                    }
                }
            }
        }
    }

    private fun checkPermission(
        context: Context,
        zone: String,
        name: String,
        isCompany: Boolean,
        callback: (Boolean) -> Unit
    ) {
        val sessionMgr = SessionManager(context)
        val sessionActive = sessionMgr.getActiveCompanyId()
        val sessionCompany = sessionMgr.getCompanyId()
        val rawZone = if (zone.isNotBlank() && zone.uppercase() != "ALL") zone else (sessionActive ?: sessionCompany ?: "OKARA")

        val targetZone = rawZone.trim().uppercase()
        val canonicalDocId = com.example.superadmin.core.CompanyDataHelper.getCanonicalDocId(targetZone)
        Log.d(TAG, "checkPermission [isCompany=$isCompany, name=$name] targetZone='$targetZone', canonicalDocId='$canonicalDocId'")

        val db = FirebaseFirestore.getInstance()

        db.collection("franchiseSettings").document(canonicalDocId).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    Log.d(TAG, "Found exact franchiseSettings doc: ${doc.id}")
                    parseSnapshot(doc, name, isCompany, callback)
                } else {
                    db.collection("franchiseSettings").get().addOnSuccessListener { querySnapshot ->
                        val matchingDoc = querySnapshot.documents.find { d ->
                            val dId = d.id.trim().uppercase()
                            val dCity = d.getString("cityName")?.trim()?.uppercase().orEmpty()
                            dId == targetZone || dCity == targetZone ||
                            com.example.superadmin.core.CompanyDataHelper.getCanonicalDocId(dId) == canonicalDocId ||
                            com.example.superadmin.core.CompanyDataHelper.getCanonicalDocId(dCity) == canonicalDocId
                        }
                        if (matchingDoc != null && matchingDoc.exists()) {
                            Log.d(TAG, "Found matching franchiseSettings doc: ${matchingDoc.id}")
                            parseSnapshot(matchingDoc, name, isCompany, callback)
                        } else {
                            Log.w(TAG, "No matching franchiseSettings doc found for '$targetZone'. Defaulting.")
                            val defaultVal = if (isCompany) false else when (name.trim().lowercase()) {
                                "movement tracking", "auto complaint monitor", "dealer webview" -> false
                                "dealer panel" -> true
                                else -> true
                            }
                            callback(defaultVal)
                        }
                    }.addOnFailureListener { e ->
                        Log.e(TAG, "Error fetching franchiseSettings collection", e)
                        val defaultVal = if (isCompany) false else when (name.trim().lowercase()) {
                            "movement tracking", "auto complaint monitor", "dealer webview" -> false
                            "dealer panel" -> true
                            else -> true
                        }
                        callback(defaultVal)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error fetching document '$canonicalDocId'", e)
                val defaultVal = if (isCompany) false else when (name.trim().lowercase()) {
                    "movement tracking", "auto complaint monitor", "dealer webview" -> false
                    "dealer panel" -> true
                    else -> true
                }
                callback(defaultVal)
            }
    }

    private fun parseSnapshot(
        snapshot: DocumentSnapshot,
        name: String,
        isCompany: Boolean,
        callback: (Boolean) -> Unit
    ) {
        if (isCompany) {
            val enabledMap = snapshot.get("enabledCompanies") as? Map<*, *>
            Log.d(TAG, "parseSnapshot [Company=$name] enabledCompanies map: $enabledMap")
            if (enabledMap != null) {
                val matchingKey = enabledMap.keys.find { it.toString().trim().equals(name.trim(), true) }
                if (matchingKey != null) {
                    val status = enabledMap[matchingKey] as? Boolean
                    if (status != null) {
                        Log.d(TAG, "Found company '$name' in map with status: $status")
                        callback(status)
                        return
                    }
                }
            }
            Log.d(TAG, "Company '$name' not found in map. Defaulting to FALSE.")
            callback(false)
        } else {
            val featuresMap = snapshot.get("enabledFeatures") as? Map<*, *>
            Log.d(TAG, "parseSnapshot [Feature=$name] enabledFeatures map: $featuresMap")
            if (featuresMap != null) {
                val matchingKey = featuresMap.keys.find { it.toString().trim().equals(name.trim(), true) }
                if (matchingKey != null) {
                    val status = featuresMap[matchingKey] as? Boolean
                    if (status != null) {
                        Log.d(TAG, "Found feature '$name' (matched key: '$matchingKey') with status: $status")
                        callback(status)
                        return
                    }
                }
            }
            val defaultVal = when (name.trim().lowercase()) {
                "movement tracking", "auto complaint monitor", "dealer webview" -> false
                "dealer panel" -> true
                else -> true
            }
            Log.d(TAG, "Feature '$name' not found in enabledFeatures map. Defaulting to $defaultVal.")
            callback(defaultVal)
        }
    }
}
