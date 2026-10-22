package com.example.superadmin

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class CityFranchiseManagementActivity : AppCompatActivity() {

    private lateinit var spinnerCities: Spinner
    private lateinit var btnEditCity: Button
    private lateinit var btnDeleteCity: Button
    private lateinit var inputNewCity: EditText
    private lateinit var btnAddCity: Button
    private lateinit var containerCompanySwitches: LinearLayout
    private lateinit var containerFeatureSwitches: LinearLayout
    private lateinit var btnSavePermissions: Button

    private val db = FirebaseFirestore.getInstance()
    private val cityList = mutableListOf<String>()
    private val companySwitches = mutableMapOf<String, Switch>()
    private val featureSwitches = mutableMapOf<String, Switch>()

    private var currentCity = "Okara"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Shift down by at least 6mm (~24-32dp) plus status bar height
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.parseColor("#F9FAFB"))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val extraPadding = (resources.displayMetrics.density * 24).toInt() // At least 6mm clearance
            val topPadding = statusBarInsets.top + extraPadding
            view.setPadding(32, topPadding, 32, 32)
            insets
        }

        // Title
        root.addView(TextView(this).apply {
            text = "City & Franchise Management"
            textSize = 20f
            setTextColor(Color.parseColor("#1F2937"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Manage company services & menu features per city (Strict Zone Isolation)"
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, 4, 0, 16)
        })

        // City Selector, Edit, Delete Row
        val cityControlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }
        spinnerCities = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        cityControlLayout.addView(spinnerCities)

        btnEditCity = Button(this).apply {
            text = "Edit City"
            textSize = 12f
            setBackgroundColor(Color.parseColor("#D97706"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.marginStart = 8
            }
        }
        cityControlLayout.addView(btnEditCity)

        btnDeleteCity = Button(this).apply {
            text = "Delete City"
            textSize = 12f
            setBackgroundColor(Color.parseColor("#DC2626"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.marginStart = 8
            }
        }
        cityControlLayout.addView(btnDeleteCity)
        root.addView(cityControlLayout)

        // Add City Row
        val addCityLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        inputNewCity = EditText(this).apply {
            hint = "New City Name"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        addCityLayout.addView(inputNewCity)

        btnAddCity = Button(this).apply {
            text = "Add City"
            textSize = 12f
            setBackgroundColor(Color.parseColor("#4F46E5"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.marginStart = 8
            }
        }
        addCityLayout.addView(btnAddCity)
        root.addView(addCityLayout)

        // Scrollable content for toggles
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }

        // Company Services Section
        scrollContent.addView(TextView(this).apply {
            text = "Company Services (EBONE, WATEEN, ZONG)"
            textSize = 15f
            setTextColor(Color.parseColor("#111827"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 12, 0, 8)
        })
        containerCompanySwitches = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollContent.addView(containerCompanySwitches)

        // Menu Features Section
        scrollContent.addView(TextView(this).apply {
            text = "Super Admin Menu Features"
            textSize = 15f
            setTextColor(Color.parseColor("#111827"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        })
        containerFeatureSwitches = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollContent.addView(containerFeatureSwitches)

        scrollView.addView(scrollContent)
        root.addView(scrollView)

        // Save Button
        btnSavePermissions = Button(this).apply {
            text = "Save Permissions for City"
            setBackgroundColor(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = 16
            }
        }
        root.addView(btnSavePermissions)

        setContentView(root)

        setupCities()
        setupListeners()
    }

    private fun setupCities() {
        db.collection("companies").get().addOnSuccessListener { compSnapshot ->
            val rawCompanyIds = mutableListOf<String>()
            val displayList = mutableListOf<String>()

            for (doc in compSnapshot.documents) {
                val cId = doc.getString("companyId")?.trim() ?: doc.id.trim()
                val ownerName = doc.getString("ownerName")?.trim() ?: doc.getString("companyName")?.trim() ?: ""
                if (cId.isNotBlank() && com.example.superadmin.core.CompanyDataHelper.isValidCompanyId(cId)) {
                    rawCompanyIds.add(cId)
                    val label = if (ownerName.isNotBlank() && !ownerName.equals(cId, true)) "$cId (Owner: $ownerName)" else cId
                    displayList.add(label)
                }
            }

            cityList.clear()
            cityList.addAll(rawCompanyIds)

            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayList).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            spinnerCities.adapter = adapter

            val sessionActiveComp = com.example.superadmin.core.SessionManager(this).getActiveCompanyId()?.trim()?.uppercase()
            if (!sessionActiveComp.isNullOrBlank() && sessionActiveComp != "ALL" && rawCompanyIds.contains(sessionActiveComp)) {
                currentCity = sessionActiveComp
            }

            if (rawCompanyIds.isNotEmpty()) {
                if (!rawCompanyIds.contains(currentCity)) {
                    currentCity = rawCompanyIds.first()
                }
                spinnerCities.setSelection(rawCompanyIds.indexOf(currentCity))
            } else {
                currentCity = ""
                containerCompanySwitches.removeAllViews()
                containerFeatureSwitches.removeAllViews()
            }

            spinnerCities.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position in rawCompanyIds.indices) {
                        currentCity = rawCompanyIds[position]
                        com.example.superadmin.core.SessionManager(this@CityFranchiseManagementActivity).saveActiveCompany(currentCity)
                        loadCityPermissions(currentCity)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }.addOnFailureListener {
            cityList.clear()
            spinnerCities.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, emptyList<String>())
        }
    }

    private fun updateSpinner() {
        setupCities()
    }

    private fun setupListeners() {
        btnAddCity.setOnClickListener {
            val newCity = inputNewCity.text.toString().trim()
            if (newCity.isNotBlank()) {
                val capitalized = newCity.replaceFirstChar { it.uppercase() }
                if (!cityList.any { it.equals(capitalized, true) }) {
                    cityList.add(capitalized)
                    updateSpinner()
                    spinnerCities.setSelection(cityList.indexOf(capitalized))
                    inputNewCity.text.clear()

                    // Initialize company-wise permissions document in franchiseSettings strictly per-company
                    val docId = com.example.superadmin.core.CompanyDataHelper.getCanonicalDocId(capitalized)
                    val companiesMap = AppFeatures.ALL_COMPANIES.associateWith { false }
                    val featuresMap = AppFeatures.ALL_FEATURES.associateWith { feat ->
                        val clean = feat.trim().lowercase()
                        if (clean.contains("movement tracking") || clean.contains("auto complaint") || clean.contains("webview")) {
                            false
                        } else {
                            true
                        }
                    }
                    val data = mapOf(
                        "enabledCompanies" to companiesMap,
                        "enabledFeatures" to featuresMap,
                        "cityName" to capitalized
                    )
                    db.collection("franchiseSettings").document(docId)
                        .set(data, SetOptions.merge())
                        .addOnSuccessListener {
                            Toast.makeText(this, "Company-wise settings created for $capitalized", Toast.LENGTH_SHORT).show()
                            loadCityPermissions(capitalized)
                        }
                } else {
                    Toast.makeText(this, "City/Company already exists", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnEditCity.setOnClickListener {
            val oldCity = currentCity
            val input = EditText(this).apply {
                setText(oldCity)
                hint = "Enter new name for city"
            }
            AlertDialog.Builder(this)
                .setTitle("Edit City: $oldCity")
                .setView(input)
                .setPositiveButton("Update") { dialog, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotBlank()) {
                        val capitalized = newName.replaceFirstChar { it.uppercase() }
                        if (!cityList.any { it.equals(capitalized, true) }) {
                            val index = cityList.indexOf(oldCity)
                            if (index != -1) {
                                cityList[index] = capitalized
                                currentCity = capitalized
                                updateSpinner()
                                migrateCityInFirestore(oldCity, capitalized)
                            }
                        } else {
                            Toast.makeText(this, "City name already exists", Toast.LENGTH_SHORT).show()
                        }
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        btnDeleteCity.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Confirmation")
                .setMessage("Are you sure you want to delete this city/franchise?")
                .setPositiveButton("Yes") { dialog, _ ->
                    dialog.dismiss()
                    authenticateAndDeleteCity(currentCity)
                }
                .setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        spinnerCities.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (position in cityList.indices) {
                    currentCity = cityList[position]
                    com.example.superadmin.core.SessionManager(this@CityFranchiseManagementActivity).saveActiveCompany(currentCity)
                    loadCityPermissions(currentCity)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        btnSavePermissions.setOnClickListener {
            saveCityPermissions(currentCity)
        }
    }

    private fun migrateCityInFirestore(oldCity: String, newCity: String) {
        val oldDocId = com.example.superadmin.core.CompanyDataHelper.getCanonicalDocId(oldCity)
        val newDocId = com.example.superadmin.core.CompanyDataHelper.getCanonicalDocId(newCity)
        db.collection("franchiseSettings").document(oldDocId).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val data = doc.data ?: emptyMap()
                    db.collection("franchiseSettings").document(newDocId)
                        .set(data)
                        .addOnSuccessListener {
                            db.collection("franchiseSettings").document(oldDocId).delete()
                            Toast.makeText(this, "City updated from $oldCity to $newCity", Toast.LENGTH_SHORT).show()
                            loadCityPermissions(currentCity)
                        }
                } else {
                    loadCityPermissions(currentCity)
                }
            }
    }

    private fun authenticateAndDeleteCity(city: String) {
        val biometricManager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        val canAuthenticate = biometricManager.canAuthenticate(authenticators)

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(
                this,
                "Security Alert: Biometric authentication (Fingerprint/Face) setup nahi hai. City delete nahi ki ja sakti.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Toast.makeText(this@CityFranchiseManagementActivity, "Authentication Successful!", Toast.LENGTH_SHORT).show()
                deleteCityFromFirestoreAndList(city)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(
                    this@CityFranchiseManagementActivity,
                    "Authentication failed or cancelled: $errString. City delete nahi hui.",
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(this@CityFranchiseManagementActivity, "Biometric match nahi hua. Dubara try karein.", Toast.LENGTH_SHORT).show()
            }
        }

        val biometricPrompt = BiometricPrompt(this, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("City Delete Verification")
            .setSubtitle("City '$city' delete karne ke liye identity verify karein")
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun deleteCityFromFirestoreAndList(city: String) {
        val docId = com.example.superadmin.core.CompanyDataHelper.getCanonicalDocId(city)
        db.collection("franchiseSettings").document(docId).delete()
            .addOnSuccessListener {
                cityList.remove(city)
                currentCity = cityList.firstOrNull() ?: ""
                updateSpinner()
                containerCompanySwitches.removeAllViews()
                containerFeatureSwitches.removeAllViews()
                Toast.makeText(this, "City deleted: $city", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to delete city: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadCityPermissions(city: String) {
        containerCompanySwitches.removeAllViews()
        containerFeatureSwitches.removeAllViews()
        companySwitches.clear()
        featureSwitches.clear()

        val docId = com.example.superadmin.core.CompanyDataHelper.getCanonicalDocId(city)
        db.collection("franchiseSettings").document(docId).get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    // Auto-initialize company-wise document if missing (default disabled for companies and sensitive features)
                    val companiesMap = AppFeatures.ALL_COMPANIES.associateWith { false }
                    val featuresMap = AppFeatures.ALL_FEATURES.associateWith { feat ->
                        val clean = feat.trim().lowercase()
                        if (clean.contains("movement tracking") || clean.contains("auto complaint") || clean.contains("webview")) {
                            false
                        } else {
                            true
                        }
                    }
                    val data = mapOf(
                        "enabledCompanies" to companiesMap,
                        "enabledFeatures" to featuresMap,
                        "cityName" to city
                    )
                    db.collection("franchiseSettings").document(docId).set(data, SetOptions.merge())
                }

                val enabledCompanies = snapshot.get("enabledCompanies") as? Map<*, *> ?: emptyMap<Any, Any>()
                val enabledFeatures = snapshot.get("enabledFeatures") as? Map<*, *> ?: emptyMap<Any, Any>()

                // Populate Companies (default to false)
                for (company in AppFeatures.ALL_COMPANIES) {
                    val isEnabled = if (snapshot.exists() && enabledCompanies.containsKey(company)) {
                        (enabledCompanies[company] as? Boolean) ?: false
                    } else {
                        false
                    }
                    val sw = Switch(this).apply {
                        text = company
                        isChecked = isEnabled
                        setPadding(12, 12, 12, 12)
                    }
                    companySwitches[company] = sw
                    containerCompanySwitches.addView(sw)
                }

                // Populate Features
                for (feature in AppFeatures.ALL_FEATURES) {
                    val clean = feature.trim().lowercase()
                    val defaultVal = if (clean.contains("movement tracking") || clean.contains("auto complaint") || clean.contains("webview")) false else true
                    val isEnabled = if (snapshot.exists() && enabledFeatures.containsKey(feature)) {
                        (enabledFeatures[feature] as? Boolean) ?: defaultVal
                    } else {
                        defaultVal
                    }
                    val sw = Switch(this).apply {
                        text = feature
                        isChecked = isEnabled
                        setPadding(12, 12, 12, 12)
                    }
                    featureSwitches[feature] = sw
                    containerFeatureSwitches.addView(sw)
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load permissions for $city", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveCityPermissions(city: String) {
        val companiesMap = mutableMapOf<String, Boolean>()
        for ((comp, sw) in companySwitches) {
            companiesMap[comp] = sw.isChecked
        }

        val featuresMapToSave = mutableMapOf<String, Boolean>()
        for ((feat, sw) in featureSwitches) {
            featuresMapToSave[feat] = sw.isChecked
        }

        val data = mapOf(
            "enabledCompanies" to companiesMap,
            "enabledFeatures" to featuresMapToSave,
            "cityName" to city
        )

        val cleanId = city.trim().uppercase()
        val noHyphenId = cleanId.replace("-", "")
        val canonicalId = com.example.superadmin.core.CompanyDataHelper.getCanonicalDocId(cleanId)

        val batch = db.batch()
        batch.set(db.collection("franchiseSettings").document(cleanId), data, SetOptions.merge())
        batch.set(db.collection("franchiseSettings").document(noHyphenId), data, SetOptions.merge())
        if (canonicalId != cleanId && canonicalId != noHyphenId) {
            batch.set(db.collection("franchiseSettings").document(canonicalId), data, SetOptions.merge())
        }

        batch.commit().addOnSuccessListener {
            CityPermissionManager.clearCache(this)
            Toast.makeText(this, "Permissions saved successfully for $city", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
