package com.example.superadmin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.superadmin.core.CompanyDataHelper
import com.example.superadmin.core.Constants
import com.example.superadmin.core.SessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject

class IspPanelSettingsActivity : AppCompatActivity() {

    private lateinit var accountsContainer: LinearLayout

    companion object {
        private const val KEY_ACCOUNTS = "all_accounts_json"

        private fun accountZone(obj: JSONObject): String = obj.optString("zone", "Okara").ifBlank { "Okara" }

        private const val ZONG_OKARA_COMPLAINT_ACCOUNT = "ABBAS046"

        private fun normalizedUsername(value: String): String =
            value.uppercase().filter { it.isLetterOrDigit() }

        private fun preferredZongOkaraComplaintAccount(arr: JSONArray): JSONObject? {
            // Pass 1: exact username ABBAS046 regardless of zone tag
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val isZong = obj.optString("isp").equals("ZONG", ignoreCase = true)
                    val isFranchiseAccount = !obj.optBoolean("isDealer", false)
                    val isAbbas046 = normalizedUsername(obj.optString("username")) == ZONG_OKARA_COMPLAINT_ACCOUNT
                    if (isZong && isFranchiseAccount && isAbbas046) return obj
                } catch (_: Exception) { }
            }
            // Pass 2: any Zong Okara account
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val isZong = obj.optString("isp").equals("ZONG", ignoreCase = true)
                    val isOkara = accountZone(obj).equals("Okara", ignoreCase = true)
                    val isFranchiseAccount = !obj.optBoolean("isDealer", false)
                    val isAbbas046 = normalizedUsername(obj.optString("username")) == ZONG_OKARA_COMPLAINT_ACCOUNT
                    if (isZong && isOkara && isFranchiseAccount && !isAbbas046) return obj
                } catch (_: Exception) { }
            }
            // Pass 3: any non-dealer Zong account
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val isZong = obj.optString("isp").equals("ZONG", ignoreCase = true)
                    val isFranchiseAccount = !obj.optBoolean("isDealer", false)
                    if (isZong && isFranchiseAccount) return obj
                } catch (_: Exception) { }
            }
            return null
        }

        fun getSavedUsername(context: Context, isp: String, zone: String = "Okara"): String? {
            val arr = safeAccountsArray(context) ?: return null

            if (isp.equals("ZONG", ignoreCase = true)) {
                if (zone.equals("Okara", ignoreCase = true)) {
                    preferredZongOkaraComplaintAccount(arr)?.let { return it.optString("username") }
                    return null
                } else if (zone.equals("Renala", ignoreCase = true)) {
                    for (i in 0 until arr.length()) {
                        try {
                            val obj = arr.getJSONObject(i)
                            val isZong = obj.optString("isp").equals("ZONG", ignoreCase = true)
                            val isRenala = accountZone(obj).equals("Renala", ignoreCase = true)
                            val isFranchiseAccount = !obj.optBoolean("isDealer", false)
                            val uname = normalizedUsername(obj.optString("username"))
                            if (isZong && isFranchiseAccount && (isRenala || uname.startsWith("RN") || uname.contains("RN")) && uname != ZONG_OKARA_COMPLAINT_ACCOUNT) {
                                return obj.getString("username")
                            }
                        } catch (_: Exception) { }
                    }
                    for (i in 0 until arr.length()) {
                        try {
                            val obj = arr.getJSONObject(i)
                            val isZong = obj.optString("isp").equals("ZONG", ignoreCase = true)
                            val isFranchiseAccount = !obj.optBoolean("isDealer", false)
                            val uname = normalizedUsername(obj.optString("username"))
                            if (isZong && isFranchiseAccount && (uname.startsWith("RN") || uname.contains("RN")) && uname != ZONG_OKARA_COMPLAINT_ACCOUNT) {
                                return obj.getString("username")
                            }
                        } catch (_: Exception) { }
                    }
                    return null
                }
                return null
            }

            // Pass 1: Non-dealer, exact ISP + exact Zone match (case-insensitive)
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) &&
                        !obj.optBoolean("isDealer", false) &&
                        accountZone(obj).equals(zone, ignoreCase = true)) {
                        return obj.getString("username")
                    }
                } catch (_: Exception) { }
            }

            // Pass 2: Non-dealer, exact ISP match (zone fallback)
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) &&
                        !obj.optBoolean("isDealer", false)) {
                        return obj.getString("username")
                    }
                } catch (_: Exception) { }
            }

            // Pass 3: Any account matching ISP
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true)) {
                        return obj.getString("username")
                    }
                } catch (_: Exception) { }
            }

            return null
        }

        fun getSavedPassword(context: Context, isp: String, zone: String = "Okara"): String? {
            val arr = safeAccountsArray(context) ?: return null

            if (isp.equals("ZONG", ignoreCase = true)) {
                if (zone.equals("Okara", ignoreCase = true)) {
                    preferredZongOkaraComplaintAccount(arr)?.let { return it.optString("password") }
                    return null
                } else if (zone.equals("Renala", ignoreCase = true)) {
                    for (i in 0 until arr.length()) {
                        try {
                            val obj = arr.getJSONObject(i)
                            val isZong = obj.optString("isp").equals("ZONG", ignoreCase = true)
                            val isRenala = accountZone(obj).equals("Renala", ignoreCase = true)
                            val isFranchiseAccount = !obj.optBoolean("isDealer", false)
                            val uname = normalizedUsername(obj.optString("username"))
                            if (isZong && isFranchiseAccount && (isRenala || uname.startsWith("RN") || uname.contains("RN")) && uname != ZONG_OKARA_COMPLAINT_ACCOUNT) {
                                return obj.getString("password")
                            }
                        } catch (_: Exception) { }
                    }
                    for (i in 0 until arr.length()) {
                        try {
                            val obj = arr.getJSONObject(i)
                            val isZong = obj.optString("isp").equals("ZONG", ignoreCase = true)
                            val isFranchiseAccount = !obj.optBoolean("isDealer", false)
                            val uname = normalizedUsername(obj.optString("username"))
                            if (isZong && isFranchiseAccount && (uname.startsWith("RN") || uname.contains("RN")) && uname != ZONG_OKARA_COMPLAINT_ACCOUNT) {
                                return obj.getString("password")
                            }
                        } catch (_: Exception) { }
                    }
                    return null
                }
                return null
            }

            // Pass 1: Non-dealer, exact ISP + exact Zone match (case-insensitive)
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) && 
                        !obj.optBoolean("isDealer", false) && 
                        accountZone(obj).equals(zone, ignoreCase = true)) {
                        return obj.getString("password")
                    }
                } catch (_: Exception) { }
            }

            // Pass 2: Non-dealer, exact ISP match
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) &&
                        !obj.optBoolean("isDealer", false)) {
                        return obj.getString("password")
                    }
                } catch (_: Exception) { }
            }

            // Pass 3: Any account matching ISP
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true)) {
                        return obj.getString("password")
                    }
                } catch (_: Exception) { }
            }
            
            return null
        }

        fun getSavedUrl(context: Context, isp: String, zone: String = "Okara"): String? {
            val arr = safeAccountsArray(context) ?: return null
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) &&
                        accountZone(obj).equals(zone, ignoreCase = true)) {
                        val u = obj.optString("url", "").trim()
                        if (u.isNotEmpty()) return u
                    }
                } catch (_: Exception) { }
            }
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true)) {
                        val u = obj.optString("url", "").trim()
                        if (u.isNotEmpty()) return u
                    }
                } catch (_: Exception) { }
            }
            return null
        }

        fun getDealerUsername(context: Context, isp: String, zone: String, dealerName: String): String? {
            val arr = safeAccountsArray(context) ?: return null
            // Pass 1: Exact match on ISP + DealerName + Zone (isDealer = true)
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) &&
                        obj.optBoolean("isDealer", false) &&
                        accountZone(obj).equals(zone, ignoreCase = true) &&
                        obj.optString("dealerName", "").trim().equals(dealerName.trim(), ignoreCase = true)
                    ) {
                        return obj.getString("username")
                    }
                } catch (_: Exception) { }
            }
            // Pass 2: Match ISP + DealerName (case-insensitive)
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) &&
                        obj.optString("dealerName", "").trim().equals(dealerName.trim(), ignoreCase = true)
                    ) {
                        return obj.getString("username")
                    }
                } catch (_: Exception) { }
            }
            // Pass 3: Match ISP + contains DealerName (partial match)
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val storedDealer = obj.optString("dealerName", "").trim()
                    if (obj.optString("isp").equals(isp, ignoreCase = true) &&
                        storedDealer.isNotEmpty() &&
                        (storedDealer.contains(dealerName, ignoreCase = true) || dealerName.contains(storedDealer, ignoreCase = true))
                    ) {
                        return obj.getString("username")
                    }
                } catch (_: Exception) { }
            }
            return null
        }

        fun getDealerPassword(context: Context, isp: String, zone: String, dealerName: String): String? {
            val arr = safeAccountsArray(context) ?: return null
            // Pass 1: Exact match on ISP + DealerName + Zone (isDealer = true)
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) &&
                        obj.optBoolean("isDealer", false) &&
                        accountZone(obj).equals(zone, ignoreCase = true) &&
                        obj.optString("dealerName", "").trim().equals(dealerName.trim(), ignoreCase = true)
                    ) {
                        return obj.getString("password")
                    }
                } catch (_: Exception) { }
            }
            // Pass 2: Match ISP + DealerName (case-insensitive)
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) &&
                        obj.optString("dealerName", "").trim().equals(dealerName.trim(), ignoreCase = true)
                    ) {
                        return obj.getString("password")
                    }
                } catch (_: Exception) { }
            }
            // Pass 3: Match ISP + contains DealerName (partial match)
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val storedDealer = obj.optString("dealerName", "").trim()
                    if (obj.optString("isp").equals(isp, ignoreCase = true) &&
                        storedDealer.isNotEmpty() &&
                        (storedDealer.contains(dealerName, ignoreCase = true) || dealerName.contains(storedDealer, ignoreCase = true))
                    ) {
                        return obj.getString("password")
                    }
                } catch (_: Exception) { }
            }
            return null
        }

        private fun safeAccountsArray(context: Context): JSONArray? {
            return try {
                val json = getPrefs(context).getString(KEY_ACCOUNTS, null) ?: return null
                val trimmed = json.trim()
                if (trimmed.startsWith("[")) {
                    JSONArray(trimmed)
                } else if (trimmed.startsWith("{")) {
                    val obj = JSONObject(trimmed)
                    val arr = JSONArray()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val accountObj = obj.optJSONObject(key)
                        if (accountObj != null) {
                            arr.put(accountObj)
                        }
                    }
                    arr
                } else {
                    null
                }
            } catch (_: Exception) { null }
        }

        fun debugListAccounts(context: Context): String {
            val arr = safeAccountsArray(context) ?: return "(no accounts saved)"
            val parts = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val isp = obj.optString("isp", "?")
                    val zone = accountZone(obj)
                    val isDealer = obj.optBoolean("isDealer", false)
                    val dealerName = obj.optString("dealerName", "")
                    parts.add(
                        if (isDealer) "$isp/$zone(dealer:$dealerName)" else "$isp/$zone"
                    )
                } catch (_: Exception) {
                    parts.add("(unreadable entry #$i)")
                }
            }
            return if (parts.isEmpty()) "(no accounts saved)" else parts.joinToString(", ")
        }

        private fun getPrefsNameForCompany(companyId: String): String {
            return "isp_panel_prefs_${companyId.lowercase().replace("-", "_")}"
        }

        private fun getPrefsForCompany(context: Context, companyId: String): SharedPreferences {
            val prefsName = getPrefsNameForCompany(companyId)
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return try {
                EncryptedSharedPreferences.create(
                    context, prefsName, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e("IspPanelSettingsActivity", "Corrupted encrypted prefs — recreating fresh", e)
                context.getSharedPreferences(prefsName, MODE_PRIVATE).edit().clear().commit()
                context.getSharedPreferences(prefsName, MODE_PRIVATE)
            }
        }

        private fun getPrefsName(context: Context): String {
            val sessionManager = SessionManager(context)
            val companyId = sessionManager.getCompanyId() ?: sessionManager.getActiveCompanyId() ?: "default"
            return getPrefsNameForCompany(companyId)
        }

        private fun getPrefs(context: Context): SharedPreferences {
            val prefsName = getPrefsName(context)
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return try {
                EncryptedSharedPreferences.create(
                    context, prefsName, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e("IspPanelSettingsActivity", "Corrupted encrypted prefs — recreating fresh", e)
                context.getSharedPreferences(prefsName, MODE_PRIVATE).edit().clear().commit()
                context.getSharedPreferences(prefsName, MODE_PRIVATE)
            }
        }
    }

    private fun canUserManageIspSettings(): Boolean {
        val sessionManager = SessionManager(this)
        val role = sessionManager.getUserRole()
        val currentUserEmail = FirebaseAuth.getInstance().currentUser?.email
        return sessionManager.isSuperAdmin() ||
               role == Constants.ROLE_COMPANY_ADMIN ||
               currentUserEmail?.equals("abbasok@gmail.com", ignoreCase = true) == true ||
               currentUserEmail?.contains("abbasok", ignoreCase = true) == true
    }

    private fun isSuperAdminUser(): Boolean {
        val sessionManager = SessionManager(this)
        val currentUserEmail = FirebaseAuth.getInstance().currentUser?.email
        return sessionManager.isSuperAdmin() ||
               currentUserEmail?.equals("abbasok@gmail.com", ignoreCase = true) == true ||
               currentUserEmail?.contains("abbasok", ignoreCase = true) == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_isp_panel_settings)

        accountsContainer = findViewById(R.id.accountsContainer)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val isSuper = isSuperAdminUser()
        val canManage = canUserManageIspSettings()
        val btnAdd = findViewById<Button>(R.id.btnAddIsp)
        val btnSync = findViewById<Button>(R.id.btnSyncCloud)
        val btnExp = findViewById<Button>(R.id.btnExportSettings)
        val btnImp = findViewById<Button>(R.id.btnImportSettings)
        val filterCardLayout = findViewById<View>(R.id.filterCardLayout)

        if (!canManage) {
            btnAdd?.visibility = View.GONE
            btnSync?.visibility = View.GONE
            btnExp?.visibility = View.GONE
            btnImp?.visibility = View.GONE
            Toast.makeText(this, "Admin Access Required (Read-Only Mode)", Toast.LENGTH_LONG).show()
        } else {
            btnAdd?.setOnClickListener { showAddAccountDialog() }
            btnSync?.setOnClickListener { syncLocalAccountsToCloudWithFeedback(true) }
            btnExp?.setOnClickListener { exportSettings() }
            btnImp?.setOnClickListener { importSettings() }
        }

        if (isSuper) {
            filterCardLayout?.visibility = View.VISIBLE
            setupCompanyFilterSpinner()
            loadAccountsUI(null)
        } else {
            filterCardLayout?.visibility = View.GONE
            val session = SessionManager(this)
            val userComp = session.getCompanyId() ?: CompanyDataHelper.getCurrentCompanyId(this) ?: "DEP-001"
            loadAccountsUI(userComp)
        }

        syncLocalAccountsToCloudWithFeedback(false)
    }

    private fun syncLocalAccountsToCloudWithFeedback(showToast: Boolean) {
        val activeCompRaw = SessionManager(this).getActiveCompanyId() ?: SessionManager(this).getCompanyId() ?: "DEP-001"
        val cleanCompId = formatCleanCompanyId(activeCompRaw) ?: activeCompRaw

        val localPrefsAccounts = try {
            val jsonStr = getPrefsForCompany(this, cleanCompId).getString(KEY_ACCOUNTS, null)
                ?: getPrefs(this).getString(KEY_ACCOUNTS, null) ?: "{}"
            JSONObject(jsonStr)
        } catch (_: Exception) {
            JSONObject()
        }

        if (localPrefsAccounts.length() == 0) {
            if (showToast) {
                Toast.makeText(this, "No local accounts found to sync.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
            .getReference("isp_panel_accounts").child(cleanCompId).get().addOnSuccessListener { snapshot ->
                val remoteJsonStr = snapshot.child("accounts_json").getValue(String::class.java) ?: "{}"
                val mergedJson = try { JSONObject(remoteJsonStr) } catch (_: Exception) { JSONObject() }

                var newAddedCount = 0
                val keys = localPrefsAccounts.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val accObj = localPrefsAccounts.getJSONObject(key)
                    if (!mergedJson.has(key)) {
                        newAddedCount++
                    }
                    mergedJson.put(key, accObj)
                }

                saveAllAccountsJson(mergedJson, cleanCompId)
                loadAccountsUI(currentFilterCompanyId)

                if (showToast) {
                    val msg = if (newAddedCount > 0) {
                        "Successfully uploaded $newAddedCount local account(s) for $cleanCompId to Cloud for SuperAdmin!"
                    } else {
                        "All local accounts for $cleanCompId are already synced to Cloud."
                    }
                    Toast.makeText(this@IspPanelSettingsActivity, msg, Toast.LENGTH_LONG).show()
                }
            }.addOnFailureListener {
                saveAllAccountsJson(localPrefsAccounts, cleanCompId)
                if (showToast) {
                    Toast.makeText(this@IspPanelSettingsActivity, "Local accounts saved and queued for Cloud sync.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun setupCompanyFilterSpinner() {
        val filterSpinner = findViewById<Spinner>(R.id.companyFilterSpinner) ?: return
        val currentCompRaw = CompanyDataHelper.getCurrentCompanyId(this) ?: "DEP-001"
        val activeCompRaw = SessionManager(this).getActiveCompanyId() ?: currentCompRaw

        val filterList = LinkedHashSet<String>()
        filterList.add("ALL (Active: $activeCompRaw)")
        formatCleanCompanyId(activeCompRaw)?.let { filterList.add(it) }
        formatCleanCompanyId(currentCompRaw)?.let { filterList.add(it) }

        FirebaseFirestore.getInstance().collection("franchiseSettings").get().addOnSuccessListener { snapshot ->
            for (doc in snapshot.documents) {
                formatCleanCompanyId(doc.id)?.let { filterList.add(it) }
            }
            FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
                .getReference("companies").get().addOnSuccessListener { rtdbSnap ->
                    for (child in rtdbSnap.children) {
                        child.key?.let { rawKey ->
                            formatCleanCompanyId(rawKey)?.let { filterList.add(it) }
                        }
                    }
                    populateFilterAdapter(filterSpinner, filterList.toList())
                }.addOnFailureListener {
                    populateFilterAdapter(filterSpinner, filterList.toList())
                }
        }.addOnFailureListener {
            populateFilterAdapter(filterSpinner, filterList.toList())
        }
    }

    private fun populateFilterAdapter(spinner: Spinner, items: List<String>) {
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            items
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = items[position]
                val compId = if (selected.startsWith("ALL")) null else selected
                loadAccountsUI(compId)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private var currentFilterCompanyId: String? = null

    private fun loadAccountsUI(targetCompanyId: String? = null) {
        currentFilterCompanyId = targetCompanyId
        accountsContainer.removeAllViews()

        val activeCompRaw = SessionManager(this).getActiveCompanyId() ?: SessionManager(this).getCompanyId() ?: "DEP-001"
        val cleanCompId = if (!targetCompanyId.isNullOrBlank()) formatCleanCompanyId(targetCompanyId) ?: targetCompanyId else formatCleanCompanyId(activeCompRaw) ?: "DEP-001"

        // 1. Immediately render local cached data first (0 latency)
        val cachedJson = if (!targetCompanyId.isNullOrBlank() && !targetCompanyId.startsWith("ALL")) {
            getPrefsForCompany(this, cleanCompId).getString(KEY_ACCOUNTS, null) ?: "{}"
        } else {
            getPrefs(this).getString(KEY_ACCOUNTS, null) ?: "{}"
        }
        val cachedAccounts = try { JSONObject(cachedJson) } catch (_: Exception) { JSONObject() }
        renderAccountsList(cachedAccounts, cleanCompId)

        // 2. Auto-fetch Live Sync from Firebase Realtime Database
        if (targetCompanyId == null || targetCompanyId.startsWith("ALL")) {
            FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
                .getReference("isp_panel_accounts").get().addOnSuccessListener { snapshot ->
                    val combined = JSONObject()
                    for (child in snapshot.children) {
                        val compKey = child.key ?: continue
                        val jsonStr = child.child("accounts_json").getValue(String::class.java) ?: continue
                        try {
                            val compObj = JSONObject(jsonStr)
                            val keys = compObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val acc = compObj.getJSONObject(k)
                                combined.put("${compKey}_$k", acc)
                            }
                        } catch (_: Exception) {}
                    }
                    if (combined.length() > 0) {
                        accountsContainer.removeAllViews()
                        renderAccountsList(combined, "ALL")
                    }
                }
        } else {
            FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
                .getReference("isp_panel_accounts").child(cleanCompId).get().addOnSuccessListener { snapshot ->
                    val jsonStr = snapshot.child("accounts_json").getValue(String::class.java)
                    if (!jsonStr.isNullOrBlank()) {
                        try {
                            val remoteAccounts = JSONObject(jsonStr)
                            getPrefsForCompany(this@IspPanelSettingsActivity, cleanCompId)
                                .edit().putString(KEY_ACCOUNTS, remoteAccounts.toString()).apply()
                            accountsContainer.removeAllViews()
                            renderAccountsList(remoteAccounts, cleanCompId)
                        } catch (e: Exception) {
                            Log.e("IspPanelSettings", "Error parsing remote JSON", e)
                        }
                    }
                }
        }
    }

    private fun renderAccountsList(accounts: JSONObject, companyTag: String) {
        val keys = accounts.keys()
        val isSuper = isSuperAdminUser()

        while (keys.hasNext()) {
            val name = keys.next()
            val obj = accounts.getJSONObject(name)
            val isp = obj.optString("isp", "EBONE")
            val zone = accountZone(obj).ifBlank { companyTag }
            val username = obj.optString("username", "")
            val password = obj.optString("password", "")
            val isDealer = obj.optBoolean("isDealer", false)
            val dealerName = obj.optString("dealerName", "")
            val urlStr = obj.optString("url", "").trim()

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(24, 24, 24, 24)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 16) }
            }

            val titleTv = TextView(this).apply {
                text = if (isDealer) "$isp ($zone) — Dealer: $dealerName" else "$isp ($zone) — Franchise ($name)"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1B5E20"))
            }

            var isPasswordVisible = false
            val detailsTv = TextView(this).apply {
                fun updateText() {
                    val passDisp = if (isSuper && isPasswordVisible) password else "••••••••"
                    text = buildString {
                        append("Username: $username\nPassword: $passDisp")
                        if (urlStr.isNotEmpty()) append("\nURL: $urlStr")
                    }
                }
                updateText()
                textSize = 14f
                setTextColor(Color.parseColor("#37474F"))
            }

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 }
            }

            val canManage = canUserManageIspSettings()
            val deleteBtn = Button(this).apply {
                text = "Delete"
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setTextColor(Color.WHITE)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                visibility = if (canManage) View.VISIBLE else View.GONE
                setOnClickListener {
                    if (isSuperAdminUser()) {
                        showDeleteConfirmDialog(name, zone)
                    } else {
                        Toast.makeText(this@IspPanelSettingsActivity, "Coming soon: Delete Account", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            if (isSuper) {
                val togglePassBtn = Button(this).apply {
                    text = "👁️ Show Password"
                    setBackgroundColor(Color.parseColor("#1565C0"))
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 8 }
                    setOnClickListener {
                        isPasswordVisible = !isPasswordVisible
                        text = if (isPasswordVisible) "🙈 Hide Password" else "👁️ Show Password"
                        val passDisp = if (isPasswordVisible) password else "••••••••"
                        detailsTv.text = buildString {
                            append("Username: $username\nPassword: $passDisp")
                            if (urlStr.isNotEmpty()) append("\nURL: $urlStr")
                        }
                    }
                }
                btnRow.addView(togglePassBtn)
            }
            btnRow.addView(deleteBtn)

            card.addView(titleTv)
            card.addView(detailsTv)
            card.addView(btnRow)
            accountsContainer.addView(card)
        }

        if (accounts.length() == 0) {
            val empty = TextView(this).apply {
                val label = if (companyTag != "ALL") "No accounts saved for $companyTag yet." else "No ISP panel accounts saved yet."
                text = "$label Click below to add one."
                textSize = 15f
                setTextColor(Color.parseColor("#546E7A"))
                setPadding(16, 16, 16, 16)
            }
            accountsContainer.addView(empty)
        }
    }

    private fun loadAllAccountsJson(): JSONObject {
        val json = getPrefs(this).getString(KEY_ACCOUNTS, null) ?: return JSONObject()
        return try { JSONObject(json) } catch (_: Exception) { JSONObject() }
    }

    private fun saveAllAccountsJson(json: JSONObject, targetCompanyId: String? = null) {
        val activeCompRaw = SessionManager(this).getActiveCompanyId() ?: SessionManager(this).getCompanyId() ?: "DEP-001"
        val cleanCompId = if (!targetCompanyId.isNullOrBlank()) formatCleanCompanyId(targetCompanyId) ?: targetCompanyId else formatCleanCompanyId(activeCompRaw) ?: "DEP-001"

        // 1. Save locally to SharedPreferences
        getPrefsForCompany(this, cleanCompId).edit().putString(KEY_ACCOUNTS, json.toString()).apply()

        // 2. Auto-sync to Firebase RTDB & Firestore
        syncAccountsToFirebase(cleanCompId, json)
    }

    private fun syncAccountsToFirebase(companyId: String, accountsJson: JSONObject) {
        val cleanCompId = formatCleanCompanyId(companyId) ?: companyId
        val jsonStr = accountsJson.toString()
        val map = HashMap<String, Any>()
        map["accounts_json"] = jsonStr
        map["company_id"] = cleanCompId
        map["updated_at"] = System.currentTimeMillis()
        map["updated_by"] = FirebaseAuth.getInstance().currentUser?.email ?: "admin"

        FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
            .getReference("isp_panel_accounts").child(cleanCompId)
            .setValue(map)

        FirebaseFirestore.getInstance().collection("ispPanelAccounts")
            .document(cleanCompId)
            .set(map, SetOptions.merge())
    }

    private fun formatCleanCompanyId(raw: String): String? {
        val clean = raw.trim().uppercase()
        if (clean == "BALANCES" || clean == "DEFAULT" || clean == "ALL") return null

        if (clean.matches(Regex("^[A-Z]{2,5}-\\d{3}$"))) {
            return clean
        }
        val noHyphenMatch = Regex("^([A-Z]{2,5})(\\d{3})$").find(clean)
        if (noHyphenMatch != null) {
            val (prefix, num) = noHyphenMatch.destructured
            return "$prefix-$num"
        }
        return null
    }

    private fun showAddAccountDialog() {
        val sessionManager = SessionManager(this)
        val isSuper = isSuperAdminUser()
        val activeCompRaw = sessionManager.getActiveCompanyId()
            ?: sessionManager.getCompanyId()
            ?: CompanyDataHelper.getCurrentCompanyId(this)
            ?: "DEP-001"

        val isViewingAll = isSuper && (activeCompRaw.equals("ALL", ignoreCase = true) || activeCompRaw.isBlank())

        val zoneSet = LinkedHashSet<String>()

        if (!isViewingAll) {
            // STRICT COMPANY FILTERING:
            // When a specific company is selected (e.g. REN-001) or for non-SuperAdmins,
            // ONLY include that active company's ID! Exclude all other companies' IDs.
            formatCleanCompanyId(activeCompRaw)?.let { zoneSet.add(it) }
            if (zoneSet.isEmpty()) {
                val currentCompRaw = CompanyDataHelper.getCurrentCompanyId(this) ?: "DEP-001"
                formatCleanCompanyId(currentCompRaw)?.let { zoneSet.add(it) }
            }
            if (zoneSet.isEmpty()) zoneSet.add("DEP-001")
            openAddAccountDialogWithZones(zoneSet.toList())
            return
        }

        // ONLY SuperAdmin viewing "ALL" can load zones for all companies across the database
        formatCleanCompanyId(activeCompRaw)?.let { zoneSet.add(it) }
        val currentCompRaw = CompanyDataHelper.getCurrentCompanyId(this) ?: "DEP-001"
        formatCleanCompanyId(currentCompRaw)?.let { zoneSet.add(it) }

        FirebaseFirestore.getInstance().collection("franchiseSettings").get().addOnSuccessListener { snapshot ->
            for (doc in snapshot.documents) {
                formatCleanCompanyId(doc.id)?.let { zoneSet.add(it) }
            }
            FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
                .getReference("companies").get().addOnSuccessListener { rtdbSnap ->
                    for (child in rtdbSnap.children) {
                        child.key?.let { rawKey ->
                            formatCleanCompanyId(rawKey)?.let { zoneSet.add(it) }
                        }
                    }
                    openAddAccountDialogWithZones(zoneSet.toList())
                }.addOnFailureListener {
                    openAddAccountDialogWithZones(zoneSet.toList())
                }
        }.addOnFailureListener {
            openAddAccountDialogWithZones(zoneSet.toList())
        }
    }

    private fun openAddAccountDialogWithZones(companyZones: List<String>) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val nameInput = EditText(this).apply { hint = "Account Nickname" }
        
        val ispInput = AutoCompleteTextView(this).apply {
            hint = "Select or Type ISP Name (e.g. EBONE, WATEEN, ZONG, JAZZ)"
            setAdapter(ArrayAdapter(
                this@IspPanelSettingsActivity,
                android.R.layout.simple_dropdown_item_1line,
                listOf("EBONE", "WATEEN", "ZONG", "JAZZ")
            ))
            threshold = 1
        }

        val urlInput = EditText(this).apply {
            hint = "ISP Login URL (optional, e.g. https://...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        val zoneSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@IspPanelSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                companyZones
            )
        }

        val isDealerBox = CheckBox(this).apply { text = "Is this a Dealer Account?" }
        val dealerNameInput = EditText(this).apply {
            hint = "Dealer Name (e.g. Akmal)"
            visibility = View.GONE
        }

        isDealerBox.setOnCheckedChangeListener { _, checked ->
            dealerNameInput.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val usernameInput = EditText(this).apply { hint = "ISP Panel Username" }
        val passwordInput = EditText(this).apply {
            hint = "ISP Panel Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(TextView(this).apply { text = "Account Nickname:" })
        layout.addView(nameInput)
        layout.addView(TextView(this).apply { text = "ISP Name (Select or Add):" })
        layout.addView(ispInput)
        layout.addView(TextView(this).apply { text = "ISP Login URL:" })
        layout.addView(urlInput)
        layout.addView(TextView(this).apply { text = "Select Zone / Branch:" })
        layout.addView(zoneSpinner)
        layout.addView(isDealerBox)
        layout.addView(dealerNameInput)
        layout.addView(TextView(this).apply { text = "Username:" })
        layout.addView(usernameInput)
        layout.addView(TextView(this).apply { text = "Password:" })
        layout.addView(passwordInput)

        val scrollView = ScrollView(this).apply {
            addView(layout)
        }

        AlertDialog.Builder(this)
            .setTitle("Add ISP Panel Account")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val isp = ispInput.text.toString().trim().ifBlank { "EBONE" }
                val url = urlInput.text.toString().trim()
                val zone = zoneSpinner.selectedItem.toString()
                val isDealer = isDealerBox.isChecked
                val dealerName = dealerNameInput.text.toString().trim()
                val username = usernameInput.text.toString().trim()
                val password = passwordInput.text.toString().trim()

                if (name.isEmpty() || username.isEmpty() || password.isEmpty() || (isDealer && dealerName.isEmpty())) {
                    Toast.makeText(this, "Please fill in all required fields.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val jsonForZoneStr = getPrefsForCompany(this, zone).getString(KEY_ACCOUNTS, null) ?: "{}"
                val jsonForZone = try { JSONObject(jsonForZoneStr) } catch (_: Exception) { JSONObject() }

                val obj = JSONObject().apply {
                    put("isp", isp)
                    put("zone", zone)
                    put("username", username)
                    put("password", password)
                    put("isDealer", isDealer)
                    if (isDealer) put("dealerName", dealerName)
                    if (url.isNotEmpty()) put("url", url)
                }
                jsonForZone.put(name, obj)
                saveAllAccountsJson(jsonForZone, zone)
                loadAccountsUI(currentFilterCompanyId)
                Toast.makeText(this, "Account saved & auto-synced to Firebase.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmDialog(accountName: String, companyId: String? = null) {
        if (!isSuperAdminUser()) {
            Toast.makeText(this, "Admin access required to delete accounts", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "Enter Security PIN or Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val targetComp = companyId ?: currentFilterCompanyId ?: SessionManager(this).getActiveCompanyId() ?: "DEP-001"
        val cleanAccountName = if (accountName.contains("_")) accountName.substringAfter("_") else accountName

        AlertDialog.Builder(this)
            .setTitle("Delete Account")
            .setMessage("Are you sure you want to delete '$cleanAccountName'? Enter security PIN (1912) or account password to confirm:")
            .setView(input)
            .setPositiveButton("Delete") { _, _ ->
                val entered = input.text.toString().trim()
                
                // Find account password from local or cloud
                var realPassword = ""
                val cleanCompId = formatCleanCompanyId(targetComp) ?: targetComp
                val prefs = getPrefsForCompany(this, cleanCompId)
                val jsonStr = prefs.getString(KEY_ACCOUNTS, null) ?: "{}"
                val all = try { JSONObject(jsonStr) } catch (_: Exception) { JSONObject() }
                val obj = all.optJSONObject(cleanAccountName) ?: all.optJSONObject(accountName)
                if (obj != null) {
                    realPassword = obj.optString("password", "")
                }

                if (entered == realPassword || entered == "1912" || realPassword.isBlank()) {
                    // 1. Remove from current company prefs
                    all.remove(accountName)
                    all.remove(cleanAccountName)
                    prefs.edit().putString(KEY_ACCOUNTS, all.toString()).apply()

                    // 2. Remove from root prefs
                    try {
                        val rootP = getPrefs(this)
                        val rStr = rootP.getString(KEY_ACCOUNTS, null) ?: "{}"
                        val rObj = JSONObject(rStr)
                        rObj.remove(accountName)
                        rObj.remove(cleanAccountName)
                        rootP.edit().putString(KEY_ACCOUNTS, rObj.toString()).apply()
                    } catch (_: Exception) {}

                    // 3. Remove from all local ISP account stores
                    listOf("wateen_accounts", "zong_accounts_prefs", "ebill_accounts").forEach { pName ->
                        try {
                            val p = getSharedPreferences(pName, MODE_PRIVATE)
                            val raw = p.getString("accounts_json", null)
                            if (raw != null) {
                                val jsonObj = JSONObject(raw)
                                jsonObj.remove(accountName)
                                jsonObj.remove(cleanAccountName)
                                p.edit().putString("accounts_json", jsonObj.toString()).apply()
                            }
                        } catch (_: Exception) {}
                    }

                    // 4. Update Cloud (RTDB & Firestore) across isp_panel_accounts
                    val rtdb = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
                    val firestore = FirebaseFirestore.getInstance()

                    // Update specific company node
                    if (cleanCompId != "ALL" && cleanCompId.isNotBlank()) {
                        val map = HashMap<String, Any>()
                        map["accounts_json"] = all.toString()
                        map["company_id"] = cleanCompId
                        map["updated_at"] = System.currentTimeMillis()
                        map["updated_by"] = FirebaseAuth.getInstance().currentUser?.email ?: "admin"

                        rtdb.getReference("isp_panel_accounts").child(cleanCompId).setValue(map)
                        firestore.collection("ispPanelAccounts").document(cleanCompId).set(map, SetOptions.merge())
                    }

                    // Scan and remove accountName and cleanAccountName from ALL cloud company nodes
                    rtdb.getReference("isp_panel_accounts").get().addOnSuccessListener { snapshot ->
                        for (child in snapshot.children) {
                            val compKey = child.key ?: continue
                            val remoteJsonStr = child.child("accounts_json").getValue(String::class.java) ?: "{}"
                            try {
                                val remoteObj = JSONObject(remoteJsonStr)
                                if (remoteObj.has(accountName) || remoteObj.has(cleanAccountName)) {
                                    remoteObj.remove(accountName)
                                    remoteObj.remove(cleanAccountName)

                                    val compPrefs = getPrefsForCompany(this@IspPanelSettingsActivity, compKey)
                                    compPrefs.edit().putString(KEY_ACCOUNTS, remoteObj.toString()).apply()

                                    val cMap = HashMap<String, Any>()
                                    cMap["accounts_json"] = remoteObj.toString()
                                    cMap["company_id"] = compKey
                                    cMap["updated_at"] = System.currentTimeMillis()
                                    cMap["updated_by"] = FirebaseAuth.getInstance().currentUser?.email ?: "admin"

                                    rtdb.getReference("isp_panel_accounts").child(compKey).setValue(cMap)
                                    firestore.collection("ispPanelAccounts").document(compKey).set(cMap, SetOptions.merge())
                                }
                            } catch (_: Exception) {}
                        }
                        loadAccountsUI(currentFilterCompanyId)
                        Toast.makeText(this@IspPanelSettingsActivity, "Account '$cleanAccountName' deleted permanently.", Toast.LENGTH_LONG).show()
                    }.addOnFailureListener {
                        loadAccountsUI(currentFilterCompanyId)
                        Toast.makeText(this@IspPanelSettingsActivity, "Account '$cleanAccountName' deleted locally.", Toast.LENGTH_LONG).show()
                    }

                } else {
                    Toast.makeText(this, "Incorrect password or pin. Deletion cancelled.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var pendingExportData: String? = null

    private val exportFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val data = pendingExportData
        if (uri != null && data != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(data.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "Settings file saved successfully!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("IspPanelSettings", "File export failed", e)
                Toast.makeText(this, "Failed to save file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportData = null
    }

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val content = contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                }
                if (!content.isNullOrBlank()) {
                    processImportContent(content)
                } else {
                    Toast.makeText(this, "Selected file is empty.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("IspPanelSettings", "File import failed", e)
                Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun encodeExportData(jsonString: String): String {
        val payload = "EBONE_ISP_CONFIG_V1::$jsonString"
        return Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun decodeImportData(encodedData: String): String? {
        return try {
            val trimmed = encodedData.trim()
            val decoded = String(Base64.decode(trimmed, Base64.NO_WRAP), Charsets.UTF_8)
            if (decoded.startsWith("EBONE_ISP_CONFIG_V1::")) {
                decoded.substring("EBONE_ISP_CONFIG_V1::".length)
            } else if (decoded.startsWith("{") || decoded.startsWith("[")) {
                decoded
            } else if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                trimmed
            } else null
        } catch (_: Exception) {
            if (encodedData.trim().startsWith("{") || encodedData.trim().startsWith("[")) encodedData.trim() else null
        }
    }

    private fun exportSettings() {
        val accounts = loadAllAccountsJson()
        if (accounts.length() == 0) {
            Toast.makeText(this, "No saved accounts to export.", Toast.LENGTH_SHORT).show()
            return
        }

        val jsonString = accounts.toString()
        val exportCode = encodeExportData(jsonString)

        val summaryList = mutableListOf<String>()
        val keys = accounts.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val obj = accounts.getJSONObject(name)
            val isp = obj.optString("isp", "EBONE")
            val zone = accountZone(obj)
            val isDealer = obj.optBoolean("isDealer", false)
            val dealerName = obj.optString("dealerName", "")
            val type = if (isDealer) "Dealer ($dealerName)" else "Franchise"
            summaryList.add("• $name: $isp ($zone) — $type")
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val summaryTv = TextView(this).apply {
            text = "Accounts to export:\n" + summaryList.joinToString("\n") + "\n\nExport Code / File Content:"
            textSize = 14f
            setTextColor(Color.parseColor("#37474F"))
            setPadding(0, 0, 0, 8)
        }

        val btnSaveFile = Button(this).apply {
            text = "📁 Save to File (.ebone / .json)"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                pendingExportData = exportCode
                exportFileLauncher.launch("isp_settings.ebone")
            }
        }

        val codeBox = EditText(this).apply {
            setText(exportCode)
            textSize = 12f
            maxLines = 4
            isFocusable = true
            isClickable = true
            setSelectAllOnFocus(true)
        }

        dialogView.addView(summaryTv)
        dialogView.addView(btnSaveFile)
        dialogView.addView(codeBox)

        AlertDialog.Builder(this)
            .setTitle("Export ISP Settings (${accounts.length()} Accounts)")
            .setView(dialogView)
            .setPositiveButton("Copy Code") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ISP Panel Settings", exportCode)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Settings code copied to clipboard!", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Share") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "SuperAdmin Admin Panel - ISP Settings Export")
                    putExtra(Intent.EXTRA_TEXT, exportCode)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Settings Export"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun importSettings() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val btnSelectFile = Button(this).apply {
            text = "📁 Select File to Import (.ebone / .json)"
            setBackgroundColor(Color.parseColor("#00838F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                importFileLauncher.launch(arrayOf("*/*", "application/json", "text/plain"))
            }
        }

        val label = TextView(this).apply {
            text = "Or paste exported settings code below:"
            textSize = 14f
            setTextColor(Color.parseColor("#37474F"))
            setPadding(0, 16, 0, 8)
        }

        val codeInput = EditText(this).apply {
            hint = "Paste code here..."
            textSize = 12f
            minLines = 3
            maxLines = 6
        }

        val btnPaste = Button(this).apply {
            text = "📋 Paste from Clipboard"
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                    codeInput.setText(text)
                } else {
                    Toast.makeText(this@IspPanelSettingsActivity, "Clipboard is empty.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        layout.addView(btnSelectFile)
        layout.addView(label)
        layout.addView(codeInput)
        layout.addView(btnPaste)

        AlertDialog.Builder(this)
            .setTitle("Import ISP Settings")
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val rawInput = codeInput.text.toString().trim()
                if (rawInput.isNotEmpty()) {
                    processImportContent(rawInput)
                } else {
                    Toast.makeText(this, "Please select a file or paste export code.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun processImportContent(rawInput: String) {
        val jsonPayload = decodeImportData(rawInput)
        if (jsonPayload.isNullOrBlank()) {
            Toast.makeText(this, "Invalid export file/code format.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val importedObj = JSONObject(jsonPayload)
            val currentObj = loadAllAccountsJson()
            var importedCount = 0

            val keys = importedObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val accountData = importedObj.optJSONObject(key)
                if (accountData != null) {
                    currentObj.put(key, accountData)
                    importedCount++
                }
            }

            if (importedCount == 0) {
                Toast.makeText(this, "No valid accounts found in file/code.", Toast.LENGTH_SHORT).show()
                return
            }

            saveAllAccountsJson(currentObj)
            loadAccountsUI()
            Toast.makeText(this, "Successfully imported $importedCount accounts!", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Log.e("IspPanelSettings", "Import failed", e)
            Toast.makeText(this, "Import failed: Invalid file content.", Toast.LENGTH_LONG).show()
        }
    }
}
