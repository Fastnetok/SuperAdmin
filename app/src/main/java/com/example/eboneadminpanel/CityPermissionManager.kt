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

    private fun normalizeZone(zone: String): String {
        val clean = zone.trim().uppercase()
        return clean.split("-", "_", " ").firstOrNull() ?: clean
    }

    fun isCompanyEnabled(context: Context, zone: String, companyName: String, callback: (Boolean) -> Unit) {
        val sessionMgr = SessionManager(context)
        val rawZone = if (zone.isNotBlank() && zone.uppercase() != "ALL") zone else (sessionMgr.getActiveCompanyId() ?: sessionMgr.getCompanyId() ?: "OKARA")
        val targetZone = rawZone.trim().uppercase()
        val prefs = getCachePrefs(context)
        val cacheKey = "${targetZone}_company_$companyName"

        // 1. Instant Cache Return (Zero Flash / Zero Delay)
        val cachedValue = if (prefs.contains(cacheKey)) {
            prefs.getBoolean(cacheKey, true)
        } else {
            true
        }
        callback(cachedValue)

        // 2. Background Firestore Sync
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

        // 1. Instant Cache Return (Zero Flash / Zero Delay)
        val cachedValue = if (prefs.contains(cacheKey)) {
            prefs.getBoolean(cacheKey, true)
        } else {
            true
        }
        callback(cachedValue)

        // 2. Background Firestore Sync
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
                        callback(if (sorted.isEmpty()) all else sorted)
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
        val baseZone = normalizeZone(targetZone)
        Log.d(TAG, "checkPermission [isCompany=$isCompany, name=$name] inputZone='$zone' -> targetZone='$targetZone', baseZone='$baseZone'")

        val db = FirebaseFirestore.getInstance()

        db.collection("franchiseSettings").get()
            .addOnSuccessListener { querySnapshot ->
                val docs = querySnapshot.documents

                // Priority 1: Exact id or cityName == targetZone
                var matchingDoc = docs.find { doc ->
                    val docId = doc.id.trim().uppercase()
                    val docCity = doc.getString("cityName")?.trim()?.uppercase().orEmpty()
                    docId == targetZone || docCity == targetZone
                }

                // Priority 2: Exact id or cityName == baseZone
                if (matchingDoc == null) {
                    matchingDoc = docs.find { doc ->
                        val docId = doc.id.trim().uppercase()
                        val docCity = doc.getString("cityName")?.trim()?.uppercase().orEmpty()
                        docId == baseZone || docCity == baseZone
                    }
                }

                // Priority 3: Starts with baseZone or targetZone
                if (matchingDoc == null) {
                    matchingDoc = docs.find { doc ->
                        val docId = doc.id.trim().uppercase()
                        val docCity = doc.getString("cityName")?.trim()?.uppercase().orEmpty()
                        docId.startsWith(baseZone) || baseZone.startsWith(docId) ||
                        docCity.startsWith(baseZone) || baseZone.startsWith(docCity) ||
                        docId.startsWith(targetZone) || targetZone.startsWith(docId)
                    }
                }

                // Priority 4: Contains baseZone or targetZone
                if (matchingDoc == null) {
                    matchingDoc = docs.find { doc ->
                        val docId = doc.id.trim().uppercase()
                        val docCity = doc.getString("cityName")?.trim()?.uppercase().orEmpty()
                        docId.contains(baseZone) || baseZone.contains(docId) ||
                        docId.contains(targetZone) || targetZone.contains(docId) ||
                        docCity.contains(baseZone) || baseZone.contains(docCity)
                    }
                }

                if (matchingDoc != null && matchingDoc.exists()) {
                    Log.d(TAG, "Found matching franchiseSettings doc (Priority search): ${matchingDoc.id} (cityName: ${matchingDoc.getString("cityName")})")
                    parseSnapshot(matchingDoc, name, isCompany, callback)
                } else {
                    Log.w(TAG, "No matching franchiseSettings doc found for zone '$targetZone' / base '$baseZone'. Defaulting to true.")
                    callback(true)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error fetching franchiseSettings collection", e)
                callback(true)
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
            val legacyKey = "${name.lowercase()}Enabled"
            if (snapshot.contains(legacyKey)) {
                val legacyStatus = snapshot.getBoolean(legacyKey) ?: true
                Log.d(TAG, "Found legacy company key '$legacyKey' with status: $legacyStatus")
                callback(legacyStatus)
            } else {
                Log.d(TAG, "Company '$name' not found in map or legacy keys. Defaulting to true.")
                callback(true)
            }
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
            Log.d(TAG, "Feature '$name' not found in enabledFeatures map. Defaulting to true.")
            callback(true)
        }
    }
}
