package com.example.superadmin

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.superadmin.core.CompanyDataHelper
import com.example.superadmin.core.SessionManager
import com.example.superadmin.databinding.ActivityAddEmployeeBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

data class ManageItem(
    val primaryKey: String,
    val name: String,
    var pin: String,
    val role: String,
    var status: String,
    val phone: String,
    var androidId: String?,
    var uid: String?,
    val allKeys: Set<String>
)

data class CompanyRef(val id: String, val name: String) {
    override fun toString(): String = if (name.isNotBlank() && name != id) "$name ($id)" else id
}

/**
 * Everything about managing employees lives on THIS ONE screen:
 *   1. Add a new employee (Name + auto-generated one-time PIN).
 *   2. "Manage Employees" — an expandable list with live Search by Name or PIN,
 *      showing Employee Details, PIN, Device Info, with Reset PIN / Block / Delete options.
 *
 * Reset PIN = Generates a new PIN for an existing employee (e.g. if phone is lost/changed),
 * invalidates old device access, and preserves all historical Firebase data (Complaints, Attendance, etc.).
 *
 * Delete = removes entry from active employee/PIN nodes. Historical logs/records in Firebase stay intact.
 *
 * Block = flips status to "Blocked" across employees, ApprovedDevices, employeePins.
 */
class AddEmployeeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddEmployeeBinding
    private val db = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
    private var currentPin: String = ""
    private var isManageListVisible = false
    private var manageListLoaded = false

    private val allManageItems = mutableListOf<ManageItem>()
    private val availableCompanies = mutableListOf<CompanyRef>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEmployeeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        generateNewPin()

        val roles = arrayOf("employee", "manager", "supervisor", "dealer")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, roles)
        binding.spinnerRole.adapter = spinnerAdapter

        loadCompaniesForSpinner()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRegeneratePin.setOnClickListener { generateNewPin() }
        binding.btnSaveEmployee.setOnClickListener { saveEmployee() }

        binding.btnToggleManage.setOnClickListener { toggleManageList() }

        binding.etSearchEmployee.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAndRenderManageList(s?.toString().orEmpty().trim())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadCompaniesForSpinner() {
        val sessionManager = SessionManager(this)
        val currentUserEmail = FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase()
        val isSuperAdmin = sessionManager.isSuperAdmin() || currentUserEmail == "abbasok@gmail.com" || currentUserEmail?.contains("abbasok") == true

        val userCompanyId = sessionManager.getCompanyId() ?: sessionManager.getActiveCompanyId() ?: CompanyDataHelper.getCurrentCompanyId(this) ?: ""

        if (!isSuperAdmin) {
            if (currentUserEmail != null) {
                FirebaseFirestore.getInstance().collection("companies")
                    .whereEqualTo("adminEmail", currentUserEmail)
                    .get()
                    .addOnSuccessListener { snap ->
                        val doc = snap.documents.firstOrNull()
                        val compId = doc?.getString("companyId") ?: doc?.id ?: userCompanyId.ifBlank { "DEP-002" }
                        sessionManager.saveSession(FirebaseAuth.getInstance().currentUser?.uid ?: "", "company_admin", compId)
                        availableCompanies.clear()
                        availableCompanies.add(CompanyRef(compId, compId))
                        setupCompanySpinnerAdapter(compId)
                        binding.spinnerCompany.isEnabled = false
                    }
                    .addOnFailureListener {
                        availableCompanies.clear()
                        val compToUse = if (userCompanyId.isNotBlank() && userCompanyId != "OKA001") userCompanyId else "DEP-002"
                        availableCompanies.add(CompanyRef(compToUse, compToUse))
                        setupCompanySpinnerAdapter(compToUse)
                        binding.spinnerCompany.isEnabled = false
                    }
            } else {
                availableCompanies.clear()
                val compToUse = if (userCompanyId.isNotBlank() && userCompanyId != "OKA001") userCompanyId else "DEP-002"
                availableCompanies.add(CompanyRef(compToUse, compToUse))
                setupCompanySpinnerAdapter(compToUse)
                binding.spinnerCompany.isEnabled = false
            }
            return
        }

        FirebaseFirestore.getInstance().collection("companies").get().addOnSuccessListener { snapshot ->
            availableCompanies.clear()
            for (doc in snapshot.documents) {
                val id = doc.id.trim()
                val name = doc.getString("companyName")?.trim().orEmpty().ifEmpty { id }
                availableCompanies.add(CompanyRef(id, name))
            }

            if (availableCompanies.isEmpty()) {
                db.getReference("companies").get().addOnSuccessListener { rtdbSnap ->
                    for (child in rtdbSnap.children) {
                        val id = child.key ?: continue
                        val name = child.child("companyName").value?.toString()?.trim().orEmpty().ifEmpty { id }
                        availableCompanies.add(CompanyRef(id, name))
                    }
                    setupCompanySpinnerAdapter(userCompanyId)
                }.addOnFailureListener {
                    setupCompanySpinnerAdapter(userCompanyId)
                }
            } else {
                setupCompanySpinnerAdapter(userCompanyId)
            }
        }.addOnFailureListener {
            setupCompanySpinnerAdapter(userCompanyId)
        }
    }

    private fun setupCompanySpinnerAdapter(userCompanyId: String) {
        if (availableCompanies.isEmpty()) {
            if (userCompanyId.isNotBlank()) {
                availableCompanies.add(CompanyRef(userCompanyId, userCompanyId))
            } else {
                availableCompanies.add(CompanyRef("DEFAULT", "Default Company"))
            }
        }

        val companyNames = availableCompanies.map { it.toString() }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, companyNames)
        binding.spinnerCompany.adapter = adapter

        val cleanUserComp = userCompanyId.replace("-", "").uppercase()
        if (cleanUserComp.isNotBlank()) {
            val idx = availableCompanies.indexOfFirst { 
                it.id.replace("-", "").uppercase() == cleanUserComp || 
                it.name.replace("-", "").uppercase() == cleanUserComp ||
                it.id.equals(userCompanyId, true) 
            }
            if (idx >= 0) {
                binding.spinnerCompany.setSelection(idx)
            } else if (availableCompanies.isNotEmpty()) {
                binding.spinnerCompany.setSelection(0)
            }
        }
    }

    // ===================== ADD EMPLOYEE =====================

    private fun generateNewPin() {
        currentPin = (100000..999999).random().toString()
        binding.tvGeneratedPin.text = currentPin
    }

    private fun saveEmployee() {
        val employeeName = binding.etEmployeeName.text.toString().trim()
        val phoneNumber = binding.etPhoneNumber.text.toString().trim()
        if (employeeName.isEmpty()) {
            showResult("Please enter the employee's name.", isError = true)
            return
        }

        binding.btnSaveEmployee.isEnabled = false

        val selectedComp = availableCompanies.getOrNull(binding.spinnerCompany.selectedItemPosition)
            ?: availableCompanies.firstOrNull()
            ?: CompanyRef("DEFAULT", "Default Company")
        val companyId = selectedComp.id
        val companyName = selectedComp.name
        val cleanCompId = companyId.replace("-", "")

        val empRef = if (companyId.isNotBlank() && companyId != "DEFAULT") db.getReference("companies").child(companyId).child("employees") else db.getReference("employees")
        val pinRef = if (companyId.isNotBlank() && companyId != "DEFAULT") db.getReference("companies").child(companyId).child("employeePins") else db.getReference("employeePins")
        val appRef = if (companyId.isNotBlank() && companyId != "DEFAULT") db.getReference("companies").child(companyId).child("ApprovedDevices") else db.getReference("ApprovedDevices")

        val cleanPhoneInput = phoneNumber.replace("+", "").replace("-", "").replace(" ", "")
        val formattedPhoneInput = if (cleanPhoneInput.startsWith("0")) "+92" + cleanPhoneInput.substring(1) else if (!cleanPhoneInput.startsWith("92")) "+92$cleanPhoneInput" else "+$cleanPhoneInput"
        val rawPhoneInput = if (cleanPhoneInput.startsWith("0")) cleanPhoneInput.substring(1) else if (cleanPhoneInput.startsWith("92")) cleanPhoneInput.substring(2) else cleanPhoneInput

        // Check for existing name, phone, or PIN within this specific company to avoid duplicates
        empRef.get().addOnSuccessListener { empSnap ->
            pinRef.get().addOnSuccessListener { pinSnap ->
                appRef.get().addOnSuccessListener { appSnap ->
                    var duplicateReason = ""

                    fun checkDuplicate(child: DataSnapshot): String? {
                        val name = child.child("employeeName").getValue(String::class.java)
                            ?: child.child("name").getValue(String::class.java)
                        val phone = child.child("phoneNumber").getValue(String::class.java)
                            ?: child.child("mobileNumber").getValue(String::class.java)
                            ?: child.child("phone").getValue(String::class.java)
                        val pin = child.child("pin").getValue(String::class.java)
                            ?: child.child("attendancePin").getValue(String::class.java)

                        val isNameMatch = name != null && name.equals(employeeName, ignoreCase = true)
                        val isPhoneMatch = phone != null && phone.isNotBlank() &&
                                (phone == phoneNumber || phone == cleanPhoneInput || phone == formattedPhoneInput || phone == rawPhoneInput)
                        val isPinMatch = pin != null && pin.isNotBlank() && pin == currentPin

                        if (!isNameMatch && !isPhoneMatch && !isPinMatch) return null

                        val itemCompId = child.child("companyId").getValue(String::class.java)
                            ?: child.child("company").getValue(String::class.java)
                            ?: child.child("companyName").getValue(String::class.java)

                        val matchesCompany = if (itemCompId.isNullOrEmpty() || companyId == "DEFAULT") true
                        else itemCompId.equals(companyId, true) || itemCompId.equals(companyName, true) || itemCompId.equals(cleanCompId, true)

                        if (!matchesCompany) return null

                        return when {
                            isNameMatch -> "Name \"$employeeName\""
                            isPhoneMatch -> "Phone Number \"$phoneNumber\""
                            else -> "PIN \"$currentPin\""
                        }
                    }

                    for (child in empSnap.children) {
                        val match = checkDuplicate(child)
                        if (match != null) { duplicateReason = match; break }
                    }
                    if (duplicateReason.isEmpty()) {
                        for (child in pinSnap.children) {
                            val match = checkDuplicate(child)
                            if (match != null) { duplicateReason = match; break }
                        }
                    }
                    if (duplicateReason.isEmpty()) {
                        for (child in appSnap.children) {
                            val match = checkDuplicate(child)
                            if (match != null) { duplicateReason = match; break }
                        }
                    }

                    if (duplicateReason.isNotEmpty()) {
                        showResult(
                            "❌ Duplicate Employee Error: An employee with $duplicateReason already exists for Company '$companyName' ($companyId)!\n\n" +
                                    "💡 Note: If the employee lost or changed their mobile device, please use 'Reset PIN' in Manage Employees instead of creating a duplicate entry.",
                            isError = true
                        )
                        binding.btnSaveEmployee.isEnabled = true
                        return@addOnSuccessListener
                    }

                    pinRef.child(currentPin).get()
                        .addOnSuccessListener { existing ->
                            if (existing.exists()) {
                                showResult("This PIN is already in use — tap Regenerate and try again.", isError = true)
                                binding.btnSaveEmployee.isEnabled = true
                                return@addOnSuccessListener
                            }

                            val selectedRole = binding.spinnerRole.selectedItem.toString().lowercase(Locale.getDefault())
                            val cleanPhone = phoneNumber.replace("+", "").replace("-", "").replace(" ", "")
                            val formattedPhone = if (cleanPhone.startsWith("0")) "+92" + cleanPhone.substring(1) else if (!cleanPhone.startsWith("92")) "+92$cleanPhone" else "+$cleanPhone"
                            val rawPhoneWithoutZero = if (cleanPhone.startsWith("0")) cleanPhone.substring(1) else if (cleanPhone.startsWith("92")) cleanPhone.substring(2) else cleanPhone

                            val data = mapOf<String, Any>(
                                "employeeName" to employeeName,
                                "name" to employeeName,
                                "phoneNumber" to phoneNumber,
                                "mobileNumber" to phoneNumber,
                                "mobile" to phoneNumber,
                                "phone" to phoneNumber,
                                "formattedPhone" to formattedPhone,
                                "cleanPhone" to cleanPhone,
                                "rawPhone" to rawPhoneWithoutZero,
                                "pin" to currentPin,
                                "attendancePin" to currentPin,
                                "registrationPin" to currentPin,
                                "employeePin" to currentPin,
                                "employeeId" to currentPin,
                                "id" to currentPin,
                                "key" to currentPin,
                                "role" to selectedRole,
                                "designation" to selectedRole,
                                "status" to "PENDING",
                                "cnic" to "",
                                "address" to "",
                                "createdAt" to System.currentTimeMillis(),
                                "companyId" to companyId,
                                "company" to companyName,
                                "companyName" to companyName,
                                "companyCode" to companyId,
                                "zone" to companyId
                            )

                            val approvedData = data.toMutableMap().apply {
                                put("status", "Approved")
                                put("deviceStatus", "Approved")
                                put("appStatus", "Approved")
                            }

                            val phoneKeys = mutableSetOf(currentPin)
                            if (phoneNumber.isNotBlank()) phoneKeys.add(phoneNumber)
                            if (cleanPhone.isNotBlank()) phoneKeys.add(cleanPhone)
                            if (formattedPhone.isNotBlank()) phoneKeys.add(formattedPhone)
                            if (rawPhoneWithoutZero.isNotBlank()) phoneKeys.add(rawPhoneWithoutZero)

                            // Save to Realtime Database master nodes
                            for (pk in phoneKeys) {
                                db.getReference("employees").child(pk).setValue(data)
                                db.getReference("employeePins").child(pk).setValue(data)
                                db.getReference("ApprovedDevices").child(pk).setValue(approvedData)
                            }

                            val pathsToUpdate = mutableSetOf<String>()
                            if (companyId.isNotBlank() && companyId != "DEFAULT") pathsToUpdate.add(companyId)
                            if (companyName.isNotBlank() && companyName != "DEFAULT") pathsToUpdate.add(companyName)
                            if (cleanCompId.isNotBlank() && cleanCompId != "DEFAULT") pathsToUpdate.add(cleanCompId)

                            for (path in pathsToUpdate) {
                                for (pk in phoneKeys) {
                                    db.getReference("companies").child(path).child("employees").child(pk).setValue(data)
                                    db.getReference("companies").child(path).child("employeePins").child(pk).setValue(data)
                                    db.getReference("companies").child(path).child("ApprovedDevices").child(pk).setValue(approvedData)
                                }
                            }

                            // Save to Firestore collections
                            val firestore = FirebaseFirestore.getInstance()
                            for (pk in phoneKeys) {
                                firestore.collection("employees").document(pk).set(data)
                                firestore.collection("employeePins").document(pk).set(data)
                                firestore.collection("ApprovedDevices").document(pk).set(approvedData)
                            }

                            for (path in pathsToUpdate) {
                                for (pk in phoneKeys) {
                                    firestore.collection("companies").document(path).collection("employees").document(pk).set(data)
                                    firestore.collection("companies").document(path).collection("employeePins").document(pk).set(data)
                                    firestore.collection("companies").document(path).collection("ApprovedDevices").document(pk).set(approvedData)
                                }
                            }

                            showResult(
                                "✅ Employee \"$employeeName\" created!\n\n" +
                                        "Send this to them via WhatsApp:\nCompany: $companyName ($companyId)\nName: $employeeName\nPhone: $phoneNumber\nPIN: $currentPin",
                                isError = false
                            )
                            Toast.makeText(this, "Employee saved successfully", Toast.LENGTH_SHORT).show()
                            binding.etEmployeeName.text.clear()
                            binding.etPhoneNumber.text.clear()
                            generateNewPin()
                            binding.btnSaveEmployee.isEnabled = true
                            if (isManageListVisible) loadManageList() // refresh if already open
                        }
                        .addOnFailureListener { e ->
                            showResult("Could not check PIN: ${e.message}", isError = true)
                            binding.btnSaveEmployee.isEnabled = true
                        }
                }.addOnFailureListener { e ->
                    showResult("Could not check employee list: ${e.message}", isError = true)
                    binding.btnSaveEmployee.isEnabled = true
                }
            }.addOnFailureListener { e ->
                showResult("Could not check employee list: ${e.message}", isError = true)
                binding.btnSaveEmployee.isEnabled = true
            }
        }.addOnFailureListener { e ->
            showResult("Could not check employee list: ${e.message}", isError = true)
            binding.btnSaveEmployee.isEnabled = true
        }
    }

    private fun showResult(message: String, isError: Boolean) {
        binding.tvResult.visibility = View.VISIBLE
        binding.tvResult.text = message
        binding.tvResult.setBackgroundResource(if (isError) R.drawable.bg_stat_danger else R.drawable.bg_stat_success)
        binding.tvResult.setTextColor(
            Color.parseColor(if (isError) "#C62828" else "#1B5E20")
        )
    }

    // ===================== MANAGE EMPLOYEES =====================

    private fun toggleManageList() {
        isManageListVisible = !isManageListVisible
        binding.layoutManageContainer.visibility = if (isManageListVisible) View.VISIBLE else View.GONE
        binding.tvToggleArrow.text = if (isManageListVisible) "▲" else "▼"

        if (isManageListVisible && !manageListLoaded) {
            loadManageList()
        }
    }

    private fun loadManageList() {
        manageListLoaded = true
        val selectedComp = availableCompanies.getOrNull(binding.spinnerCompany.selectedItemPosition)
        val selectedCompId = selectedComp?.id.orEmpty()
        val selectedCompName = selectedComp?.name.orEmpty()
        val isSuper = CompanyDataHelper.isSuperAdmin(this)

        fun belongsToSelection(snapshot: DataSnapshot, pathCompanyKey: String? = null): Boolean {
            val itemCompId = snapshot.child("companyId").getValue(String::class.java)
                ?: snapshot.child("company").getValue(String::class.java)
                ?: snapshot.child("companyName").getValue(String::class.java)
                ?: pathCompanyKey

            if (selectedCompId.isNotBlank() && selectedCompId != "DEFAULT") {
                if (itemCompId != null && (itemCompId.equals(selectedCompId, true) || itemCompId.equals(selectedCompName, true) || itemCompId.equals(selectedCompId.replace("-", ""), true))) {
                    return true
                }
                // If snapshot is at root and has no companyId set, show for all
                if (itemCompId.isNullOrBlank() && isSuper) return true
                return false
            }

            return CompanyDataHelper.belongsToCurrentCompany(this, snapshot)
        }

        db.getReference("employees").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(empSnap: DataSnapshot) {
                db.getReference("ApprovedDevices").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(appSnap: DataSnapshot) {
                        db.getReference("employeePins").addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(pinSnap: DataSnapshot) {
                                db.getReference("companies").addListenerForSingleValueEvent(object : ValueEventListener {
                                    override fun onDataChange(companiesSnap: DataSnapshot) {

                                        class ManageItemBuilder(
                                            var primaryKey: String = "",
                                            var name: String = "",
                                            var pin: String = "",
                                            var role: String = "employee",
                                            var status: String = "PENDING",
                                            var phone: String = "",
                                            var androidId: String? = null,
                                            var uid: String? = null,
                                            val allKeys: MutableSet<String> = mutableSetOf()
                                        )

                                        val nameToManageItemMap = mutableMapOf<String, ManageItemBuilder>()

                                        fun parseChild(child: DataSnapshot, sourceDefaultStatus: String, pathCompanyKey: String? = null) {
                                            if (!belongsToSelection(child, pathCompanyKey)) return
                                            val k = child.key ?: return
                                            val rawName = child.child("employeeName").getValue(String::class.java)
                                                ?.takeIf { it.isNotBlank() }
                                                ?: child.child("name").getValue(String::class.java)
                                                ?: return

                                            val normName = rawName.trim().lowercase(Locale.getDefault())

                                            val builder = nameToManageItemMap.getOrPut(normName) {
                                                ManageItemBuilder(primaryKey = k, name = rawName, status = sourceDefaultStatus)
                                            }

                                            builder.allKeys.add(k)

                                            val fetchedPin = child.child("attendancePin").getValue(String::class.java)
                                                ?.takeIf { it.isNotBlank() }
                                                ?: child.child("pin").getValue(String::class.java)
                                                ?: if (k.length in 4..8 && k.all { it.isDigit() }) k else ""

                                            if (fetchedPin.isNotBlank()) {
                                                builder.pin = fetchedPin
                                            }

                                            val fetchedRole = child.child("role").getValue(String::class.java)
                                            if (!fetchedRole.isNullOrBlank()) {
                                                builder.role = fetchedRole
                                            }

                                            val fetchedPhone = child.child("phoneNumber").getValue(String::class.java)
                                                ?.takeIf { it.isNotBlank() }
                                                ?: child.child("mobileNumber").getValue(String::class.java)
                                            if (!fetchedPhone.isNullOrBlank()) {
                                                builder.phone = fetchedPhone
                                            }

                                            val fetchedAndroidId = child.child("linkedAndroidId").getValue(String::class.java)
                                                ?: child.child("androidId").getValue(String::class.java)
                                            if (!fetchedAndroidId.isNullOrBlank()) {
                                                builder.androidId = fetchedAndroidId
                                            }

                                            val fetchedUid = child.child("linkedUid").getValue(String::class.java)
                                                ?: child.child("uid").getValue(String::class.java)
                                            if (!fetchedUid.isNullOrBlank()) {
                                                builder.uid = fetchedUid
                                            }

                                            val st = child.child("status").getValue(String::class.java) ?: sourceDefaultStatus
                                            if (st.equals("Approved", true) || st.equals("Blocked", true) || st.equals("ONLINE", true)) {
                                                builder.status = st
                                                builder.primaryKey = k
                                            } else if (builder.status == "PENDING" && st.isNotBlank()) {
                                                builder.status = st
                                            }

                                            if (builder.name.isBlank() || builder.name == normName) {
                                                builder.name = rawName
                                            }
                                        }

                                        // 1. Parse device approvals and employee nodes first
                                        for (child in appSnap.children) parseChild(child, "Approved")
                                        for (child in empSnap.children) parseChild(child, "Approved")

                                        for (cSnap in companiesSnap.children) {
                                            val compKey = cSnap.key ?: continue
                                            val cEmps = cSnap.child("employees")
                                            val cApps = cSnap.child("ApprovedDevices")

                                            for (child in cApps.children) parseChild(child, "Approved", compKey)
                                            for (child in cEmps.children) parseChild(child, "Approved", compKey)
                                        }

                                        // 2. LAST: Parse employeePins so generated PINs override any old employee records
                                        for (child in pinSnap.children) parseChild(child, "PENDING")

                                        for (cSnap in companiesSnap.children) {
                                            val compKey = cSnap.key ?: continue
                                            val cPins = cSnap.child("employeePins")

                                            for (child in cPins.children) parseChild(child, "PENDING", compKey)
                                        }

                                        allManageItems.clear()
                                        for ((_, b) in nameToManageItemMap) {
                                            allManageItems.add(
                                                ManageItem(
                                                    primaryKey = if (b.primaryKey.isNotBlank()) b.primaryKey else b.allKeys.firstOrNull().orEmpty(),
                                                    name = b.name,
                                                    pin = b.pin,
                                                    role = b.role,
                                                    status = b.status,
                                                    phone = b.phone,
                                                    androidId = b.androidId,
                                                    uid = b.uid,
                                                    allKeys = b.allKeys
                                                )
                                            )
                                        }

                                        allManageItems.sortBy { it.name.lowercase(Locale.getDefault()) }

                                        filterAndRenderManageList(binding.etSearchEmployee.text.toString().trim())
                                    }

                                    override fun onCancelled(error: DatabaseError) {}
                                })
                            }

                            override fun onCancelled(error: DatabaseError) {}
                        })
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun filterAndRenderManageList(query: String) {
        binding.layoutManageList.removeAllViews()

        val filtered = if (query.isEmpty()) {
            allManageItems
        } else {
            allManageItems.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.pin.contains(query, ignoreCase = true) ||
                        it.phone.contains(query, ignoreCase = true) ||
                        it.primaryKey.contains(query, ignoreCase = true)
            }
        }

        if (filtered.isEmpty()) {
            val empty = TextView(this@AddEmployeeActivity).apply {
                text = if (query.isEmpty()) "No employees found." else "No employee matching \"$query\""
                setPadding(12, 12, 12, 12)
                setTextColor(Color.parseColor("#757575"))
            }
            binding.layoutManageList.addView(empty)
            return
        }

        for (item in filtered) {
            val row = LayoutInflater.from(this@AddEmployeeActivity)
                .inflate(R.layout.item_manage_employee, binding.layoutManageList, false)

            row.findViewById<TextView>(R.id.tvRowName).text = item.name

            val statusView = row.findViewById<TextView>(R.id.tvRowStatus)
            statusView.text = item.status
            statusView.setTextColor(
                Color.parseColor(
                    if (item.status.equals("Blocked", true)) "#C62828" else "#2E7D32"
                )
            )

            val tvPin = row.findViewById<TextView>(R.id.tvRowPin)
            tvPin.text = "🔑 PIN: ${item.pin.ifEmpty { "Not Set" }} | Role: ${item.role}"

            val tvDevice = row.findViewById<TextView>(R.id.tvRowDeviceInfo)
            val devInfoText = buildString {
                append("📱 Phone: ").append(item.phone.ifEmpty { "N/A" })
                if (!item.androidId.isNullOrBlank()) {
                    append(" | Device: ").append(item.androidId)
                }
                if (!item.uid.isNullOrBlank()) {
                    append(" | UID: ").append(item.uid)
                }
            }
            tvDevice.text = devInfoText

            val resetPinBtn = row.findViewById<Button>(R.id.btnRowResetPin)
            val deregisterBtn = row.findViewById<Button>(R.id.btnRowDeregister)
            val blockBtn = row.findViewById<Button>(R.id.btnRowBlock)
            val deleteBtn = row.findViewById<Button>(R.id.btnRowDelete)

            blockBtn.text = if (item.status.equals("Blocked", true)) "Unblock" else "Block"

            resetPinBtn.setOnClickListener {
                resetEmployeePin(item)
            }

            deregisterBtn.setOnClickListener {
                deregisterEmployeeDevice(item)
            }

            blockBtn.setOnClickListener {
                blockOrUnblockEmployee(item)
            }

            deleteBtn.setOnClickListener {
                deleteEmployee(item)
            }

            binding.layoutManageList.addView(row)
        }
    }

    private fun deregisterEmployeeDevice(item: ManageItem) {
        val oldPin = item.pin
        val oldAndroidId = item.androidId
        val oldUid = item.uid
        val newPin = (100000..999999).random().toString()

        AlertDialog.Builder(this)
            .setTitle("Deregister Device & Reset PIN — ${item.name}")
            .setMessage(
                "Deregister device and reset PIN for ${item.name} (${item.phone})?\n\n" +
                        "• The employee's current phone will be immediately unlinked and logged out in real-time.\n" +
                        "• Old PIN ($oldPin) will be destroyed.\n" +
                        "• NEW Registration PIN ($newPin) will be assigned immediately.\n" +
                        "• All attendance, GPS movement tracking, fuel logs, and complaints remain 100% saved in Firebase.\n\n" +
                        "Proceed with Deregistration & PIN Change?"
            )
            .setPositiveButton("Deregister & Change PIN") { _, _ ->
                val selectedComp = availableCompanies.getOrNull(binding.spinnerCompany.selectedItemPosition)
                    ?: availableCompanies.firstOrNull()
                    ?: CompanyRef("DEFAULT", "Default Company")
                val companyId = selectedComp.id
                val companyName = selectedComp.name
                val cleanCompId = companyId.replace("-", "")

                val pathsToUpdate = mutableSetOf<String>()
                if (companyId.isNotBlank() && companyId != "DEFAULT") pathsToUpdate.add(companyId)
                if (companyName.isNotBlank() && companyName != "DEFAULT") pathsToUpdate.add(companyName)
                if (cleanCompId.isNotBlank() && cleanCompId != "DEFAULT") pathsToUpdate.add(cleanCompId)

                val cleanPhone = item.phone.replace("+", "").replace("-", "").replace(" ", "")
                val formattedPhone = if (cleanPhone.startsWith("0")) "+92" + cleanPhone.substring(1) else if (!cleanPhone.startsWith("92")) "+92$cleanPhone" else "+$cleanPhone"
                val rawPhoneWithoutZero = if (cleanPhone.startsWith("0")) cleanPhone.substring(1) else if (cleanPhone.startsWith("92")) cleanPhone.substring(2) else cleanPhone

                // 1. Gather all target keys to revoke BEFORE mutating in-memory item
                val allDeviceKeysToRevoke = mutableSetOf<String>()
                allDeviceKeysToRevoke.addAll(item.allKeys)
                if (!oldAndroidId.isNullOrBlank()) allDeviceKeysToRevoke.add(oldAndroidId)
                if (!oldUid.isNullOrBlank()) allDeviceKeysToRevoke.add(oldUid)
                if (item.phone.isNotBlank()) allDeviceKeysToRevoke.add(item.phone)
                if (cleanPhone.isNotBlank()) allDeviceKeysToRevoke.add(cleanPhone)
                if (formattedPhone.isNotBlank()) allDeviceKeysToRevoke.add(formattedPhone)
                if (rawPhoneWithoutZero.isNotBlank()) allDeviceKeysToRevoke.add(rawPhoneWithoutZero)
                if (oldPin.isNotBlank()) allDeviceKeysToRevoke.add(oldPin)

                // 2. Instantly update in-memory object & re-render UI list so row PIN updates immediately!
                item.pin = newPin
                item.status = "Deregistered"
                item.androidId = null
                item.uid = null
                filterAndRenderManageList(binding.etSearchEmployee.text.toString().trim())

                // 3. First broadcast explicit REVOKED status update so real-time listeners on employee phone fire auto-logout
                val revocationSignal = mapOf<String, Any?>(
                    "status" to "Deregistered",
                    "deviceStatus" to "REVOKED",
                    "sessionStatus" to "LOGOUT",
                    "isOnline" to false,
                    "lastUpdate" to System.currentTimeMillis()
                )

                for (devKey in allDeviceKeysToRevoke) {
                    db.getReference("ApprovedDevices").child(devKey).updateChildren(revocationSignal)
                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("ApprovedDevices").child(devKey).updateChildren(revocationSignal)
                    }
                }

                // 4. Remove device approval nodes and old PIN nodes
                for (devKey in allDeviceKeysToRevoke) {
                    db.getReference("ApprovedDevices").child(devKey).removeValue()
                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("ApprovedDevices").child(devKey).removeValue()
                    }
                }

                if (oldPin.isNotBlank()) {
                    db.getReference("employeePins").child(oldPin).removeValue()
                    db.getReference("employees").child(oldPin).removeValue()
                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("employeePins").child(oldPin).removeValue()
                        db.getReference("companies").child(path).child("employees").child(oldPin).removeValue()
                    }
                }

                // 3. Update employee records with new PIN & Deregistered status
                val deregisteredMap = mapOf<String, Any?>(
                    "status" to "Deregistered",
                    "deviceStatus" to "REVOKED",
                    "sessionStatus" to "LOGOUT",
                    "pin" to newPin,
                    "attendancePin" to newPin,
                    "registrationPin" to newPin,
                    "linkedAndroidId" to null,
                    "androidId" to null,
                    "linkedUid" to null,
                    "uid" to null
                )

                for (key in item.allKeys) {
                    db.getReference("employees").child(key).updateChildren(deregisteredMap)
                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("employees").child(key).updateChildren(deregisteredMap)
                    }
                }

                // 4. Save new PIN node in employeePins
                val newPinData = mapOf<String, Any>(
                    "employeeName" to item.name,
                    "name" to item.name,
                    "phoneNumber" to item.phone,
                    "mobileNumber" to item.phone,
                    "phone" to item.phone,
                    "formattedPhone" to formattedPhone,
                    "cleanPhone" to cleanPhone,
                    "rawPhone" to rawPhoneWithoutZero,
                    "pin" to newPin,
                    "attendancePin" to newPin,
                    "registrationPin" to newPin,
                    "role" to item.role,
                    "status" to "PENDING",
                    "createdAt" to System.currentTimeMillis(),
                    "companyId" to companyId,
                    "company" to companyName,
                    "companyName" to companyName,
                    "companyCode" to companyId
                )

                db.getReference("employeePins").child(newPin).setValue(newPinData)
                for (path in pathsToUpdate) {
                    db.getReference("companies").child(path).child("employeePins").child(newPin).setValue(newPinData)
                }

                // 5. Sync to Firestore
                val firestore = FirebaseFirestore.getInstance()
                for (devKey in allDeviceKeysToRevoke) {
                    firestore.collection("ApprovedDevices").document(devKey).delete()
                    for (path in pathsToUpdate) {
                        firestore.collection("companies").document(path).collection("ApprovedDevices").document(devKey).delete()
                    }
                }

                for (key in item.allKeys) {
                    firestore.collection("employees").document(key).update(
                        mapOf(
                            "status" to "Deregistered",
                            "pin" to newPin,
                            "linkedAndroidId" to null,
                            "linkedUid" to null
                        )
                    )
                }

                // 6. Log Admin Event
                val logData = mapOf<String, Any>(
                    "title" to "📱 App Unlinked & PIN Reset",
                    "message" to "Device for ${item.name} (${item.phone}) was unlinked in real-time. New PIN: $newPin.",
                    "employeeName" to item.name,
                    "phone" to item.phone,
                    "newPin" to newPin,
                    "companyId" to companyId,
                    "companyName" to companyName,
                    "timestamp" to System.currentTimeMillis()
                )
                db.getReference("adminNotifications").push().setValue(logData)
                if (companyId.isNotBlank() && companyId != "DEFAULT") {
                    db.getReference("companies").child(companyId).child("companyLogs").push().setValue(logData)
                }

                AlertDialog.Builder(this)
                    .setTitle("✅ Device Unlinked & PIN Changed")
                    .setMessage(
                        "Deregistered successfully!\n\n" +
                                "👤 Employee: ${item.name}\n" +
                                "📱 Phone: ${item.phone}\n\n" +
                                "🔑 NEW REGISTRATION PIN: $newPin\n\n" +
                                "• The old phone has been logged out in real-time.\n" +
                                "• Employee PIN on screen updated to $newPin."
                    )
                    .setPositiveButton("OK", null)
                    .show()

                if (isManageListVisible) loadManageList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetEmployeePin(item: ManageItem) {
        val oldPin = item.pin
        val oldAndroidId = item.androidId
        val oldUid = item.uid
        val newPin = (100000..999999).random().toString()

        AlertDialog.Builder(this)
            .setTitle("Reset PIN — ${item.name}")
            .setMessage(
                "Reset PIN for ${item.name}?\n\n" +
                        "• A new 6-digit registration PIN will be generated ($newPin).\n" +
                        "• Old device access and PIN will be invalidated.\n" +
                        "• All Complaints, Attendance, Fuel Logs, and History in Firebase for ${item.name} will be safely preserved.\n\n" +
                        "Proceed with PIN Reset?"
            )
            .setPositiveButton("Reset PIN") { _, _ ->
                val selectedComp = availableCompanies.getOrNull(binding.spinnerCompany.selectedItemPosition)
                    ?: availableCompanies.firstOrNull()
                    ?: CompanyRef("DEFAULT", "Default Company")
                val companyId = selectedComp.id
                val companyName = selectedComp.name
                val cleanCompId = companyId.replace("-", "")

                val cleanPhone = item.phone.replace("+", "").replace("-", "").replace(" ", "")
                val formattedPhone = if (cleanPhone.startsWith("0")) "+92" + cleanPhone.substring(1) else if (!cleanPhone.startsWith("92")) "+92$cleanPhone" else "+$cleanPhone"
                val rawPhoneWithoutZero = if (cleanPhone.startsWith("0")) cleanPhone.substring(1) else if (cleanPhone.startsWith("92")) cleanPhone.substring(2) else cleanPhone

                val pathsToUpdate = mutableSetOf<String>()
                if (companyId.isNotBlank() && companyId != "DEFAULT") pathsToUpdate.add(companyId)
                if (companyName.isNotBlank() && companyName != "DEFAULT") pathsToUpdate.add(companyName)
                if (cleanCompId.isNotBlank() && cleanCompId != "DEFAULT") pathsToUpdate.add(cleanCompId)

                // 1. Gather all target keys to revoke BEFORE mutating in-memory item
                val allDeviceKeysToRevoke = mutableSetOf<String>()
                allDeviceKeysToRevoke.addAll(item.allKeys)
                if (!oldAndroidId.isNullOrBlank()) allDeviceKeysToRevoke.add(oldAndroidId)
                if (!oldUid.isNullOrBlank()) allDeviceKeysToRevoke.add(oldUid)
                if (item.phone.isNotBlank()) allDeviceKeysToRevoke.add(item.phone)
                if (cleanPhone.isNotBlank()) allDeviceKeysToRevoke.add(cleanPhone)
                if (formattedPhone.isNotBlank()) allDeviceKeysToRevoke.add(formattedPhone)
                if (rawPhoneWithoutZero.isNotBlank()) allDeviceKeysToRevoke.add(rawPhoneWithoutZero)
                if (oldPin.isNotBlank()) allDeviceKeysToRevoke.add(oldPin)

                // 2. Immediately update local memory item & re-render UI list so PIN text on screen updates instantly!
                item.pin = newPin
                item.status = "PENDING"
                item.androidId = null
                item.uid = null
                filterAndRenderManageList(binding.etSearchEmployee.text.toString().trim())

                val newPinData = mapOf<String, Any>(
                    "employeeName" to item.name,
                    "name" to item.name,
                    "phoneNumber" to item.phone,
                    "mobileNumber" to item.phone,
                    "mobile" to item.phone,
                    "phone" to item.phone,
                    "formattedPhone" to formattedPhone,
                    "cleanPhone" to cleanPhone,
                    "rawPhone" to rawPhoneWithoutZero,
                    "pin" to newPin,
                    "attendancePin" to newPin,
                    "registrationPin" to newPin,
                    "employeeId" to newPin,
                    "id" to newPin,
                    "key" to newPin,
                    "role" to item.role,
                    "designation" to item.role,
                    "status" to "PENDING",
                    "cnic" to "",
                    "address" to "",
                    "createdAt" to System.currentTimeMillis(),
                    "companyId" to companyId,
                    "company" to companyName,
                    "companyName" to companyName,
                    "companyCode" to companyId
                )

                // 3. First broadcast explicit REVOKED status update so real-time listeners on employee phone fire auto-logout
                val revocationSignal = mapOf<String, Any?>(
                    "status" to "PENDING",
                    "deviceStatus" to "REVOKED",
                    "sessionStatus" to "LOGOUT",
                    "isOnline" to false,
                    "lastUpdate" to System.currentTimeMillis()
                )

                for (devKey in allDeviceKeysToRevoke) {
                    db.getReference("ApprovedDevices").child(devKey).updateChildren(revocationSignal)
                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("ApprovedDevices").child(devKey).updateChildren(revocationSignal)
                    }
                }

                // 4. Remove all old device approvals from ApprovedDevices (Forces immediate logout on old phone)
                for (devKey in allDeviceKeysToRevoke) {
                    db.getReference("ApprovedDevices").child(devKey).removeValue()
                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("ApprovedDevices").child(devKey).removeValue()
                    }
                }

                // 2. Unbind device IDs and set PENDING status in employee records
                val pendingMap = mapOf<String, Any?>(
                    "pin" to newPin,
                    "attendancePin" to newPin,
                    "registrationPin" to newPin,
                    "status" to "PENDING",
                    "deviceStatus" to "REVOKED",
                    "sessionStatus" to "LOGOUT",
                    "linkedAndroidId" to null,
                    "androidId" to null,
                    "linkedUid" to null,
                    "uid" to null
                )

                for (key in item.allKeys) {
                    db.getReference("employees").child(key).updateChildren(pendingMap)
                    db.getReference("employeePins").child(key).updateChildren(pendingMap)
                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("employees").child(key).updateChildren(pendingMap)
                        db.getReference("companies").child(path).child("employeePins").child(key).updateChildren(pendingMap)
                    }
                }

                val phoneKeysToSet = mutableSetOf(newPin)
                if (item.phone.isNotBlank()) phoneKeysToSet.add(item.phone)
                if (cleanPhone.isNotBlank()) phoneKeysToSet.add(cleanPhone)
                if (formattedPhone.isNotBlank()) phoneKeysToSet.add(formattedPhone)
                if (rawPhoneWithoutZero.isNotBlank()) phoneKeysToSet.add(rawPhoneWithoutZero)

                // 3. Write new PIN entries in employeePins and employees as PENDING
                for (k in phoneKeysToSet) {
                    db.getReference("employeePins").child(k).setValue(newPinData)
                    db.getReference("employees").child(k).setValue(newPinData)

                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("employeePins").child(k).setValue(newPinData)
                        db.getReference("companies").child(path).child("employees").child(k).setValue(newPinData)
                    }
                }

                // 4. Clean up old PINs
                for (key in item.allKeys) {
                    if (key != newPin && !phoneKeysToSet.contains(key)) {
                        db.getReference("employeePins").child(key).removeValue()
                        for (path in pathsToUpdate) {
                            db.getReference("companies").child(path).child("employeePins").child(key).removeValue()
                        }
                    }
                }
                if (item.pin.isNotEmpty() && item.pin != newPin && !phoneKeysToSet.contains(item.pin)) {
                    db.getReference("employeePins").child(item.pin).removeValue()
                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("employeePins").child(item.pin).removeValue()
                    }
                }

                // 5. Sync revocation & new PIN to Firestore
                val firestore = FirebaseFirestore.getInstance()
                for (devKey in allDeviceKeysToRevoke) {
                    firestore.collection("ApprovedDevices").document(devKey).delete()
                    for (path in pathsToUpdate) {
                        firestore.collection("companies").document(path).collection("ApprovedDevices").document(devKey).delete()
                    }
                }

                for (k in phoneKeysToSet) {
                    firestore.collection("employeePins").document(k).set(newPinData)
                    firestore.collection("employees").document(k).set(newPinData)

                    for (path in pathsToUpdate) {
                        firestore.collection("companies").document(path).collection("employeePins").document(k).set(newPinData)
                        firestore.collection("companies").document(path).collection("employees").document(k).set(newPinData)
                    }
                }

                for (key in item.allKeys) {
                    firestore.collection("employees").document(key).update(
                        mapOf(
                            "attendancePin" to newPin,
                            "status" to "PENDING",
                            "linkedAndroidId" to null,
                            "linkedUid" to null
                        )
                    )
                }

                AlertDialog.Builder(this)
                    .setTitle("✅ PIN Reset Successful")
                    .setMessage(
                        "New PIN generated for ${item.name}:\n\n" +
                                "📍 New PIN: $newPin\n" +
                                "📱 Employee: ${item.name} (${item.phone})\n\n" +
                                "Send this new PIN to the employee via WhatsApp so they can register on their new mobile. All past history remains intact."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun blockOrUnblockEmployee(item: ManageItem) {
        val nowBlocked = item.status.equals("Blocked", true)
        val newStatus = if (nowBlocked) "Approved" else "Blocked"
        val actionLabel = if (nowBlocked) "Unblock" else "Block"

        AlertDialog.Builder(this)
            .setTitle("$actionLabel Employee")
            .setMessage("$actionLabel ${item.name}? Existing logs and historical data will stay safe in Firebase.")
            .setPositiveButton(actionLabel) { _, _ ->
                for (key in item.allKeys) {
                    db.getReference("employees").child(key).child("status").setValue(newStatus)
                    db.getReference("employeePins").child(key).child("status").setValue(newStatus)
                    db.getReference("ApprovedDevices").child(key).child("status").setValue(newStatus)
                }
                if (item.pin.isNotEmpty()) {
                    db.getReference("employeePins").child(item.pin).child("status").setValue(newStatus)
                }
                val devId = item.androidId
                if (!devId.isNullOrBlank()) {
                    db.getReference("ApprovedDevices").child(devId).child("status").setValue(newStatus)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteEmployee(item: ManageItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Employee")
            .setMessage(
                "Remove ${item.name} from active employee list?\n\n" +
                        "Note: Historical complaints, attendance, and logs in Firebase are permanently preserved. " +
                        "If ${item.name} is added again later, all previous data will link up automatically."
            )
            .setPositiveButton("Delete") { _, _ ->
                val firestore = FirebaseFirestore.getInstance()
                val selectedComp = availableCompanies.getOrNull(binding.spinnerCompany.selectedItemPosition)
                val companyId = selectedComp?.id.orEmpty()
                val companyName = selectedComp?.name.orEmpty()

                val paths = mutableSetOf<String>()
                if (companyId.isNotBlank()) paths.add(companyId)
                if (companyName.isNotBlank()) paths.add(companyName)

                for (key in item.allKeys) {
                    db.getReference("employees").child(key).removeValue()
                    db.getReference("employeePins").child(key).removeValue()
                    db.getReference("ApprovedDevices").child(key).removeValue()

                    firestore.collection("employees").document(key).delete()
                    firestore.collection("employeePins").document(key).delete()
                    firestore.collection("ApprovedDevices").document(key).delete()

                    for (path in paths) {
                        db.getReference("companies").child(path).child("employees").child(key).removeValue()
                        db.getReference("companies").child(path).child("employeePins").child(key).removeValue()
                        db.getReference("companies").child(path).child("ApprovedDevices").child(key).removeValue()

                        firestore.collection("companies").document(path).collection("employees").document(key).delete()
                        firestore.collection("companies").document(path).collection("employeePins").document(key).delete()
                        firestore.collection("companies").document(path).collection("ApprovedDevices").document(key).delete()
                    }
                }
                if (item.pin.isNotEmpty()) {
                    db.getReference("employeePins").child(item.pin).removeValue()
                    firestore.collection("employeePins").document(item.pin).delete()
                    for (path in paths) {
                        db.getReference("companies").child(path).child("employeePins").child(item.pin).removeValue()
                        firestore.collection("companies").document(path).collection("employeePins").document(item.pin).delete()
                    }
                }
                val devId = item.androidId
                if (!devId.isNullOrBlank()) {
                    db.getReference("ApprovedDevices").child(devId).removeValue()
                    firestore.collection("ApprovedDevices").document(devId).delete()
                    for (path in paths) {
                        db.getReference("companies").child(path).child("ApprovedDevices").child(devId).removeValue()
                        firestore.collection("companies").document(path).collection("ApprovedDevices").document(devId).delete()
                    }
                }
                Toast.makeText(this, "${item.name} deleted from active list", Toast.LENGTH_SHORT).show()
                allManageItems.remove(item)
                filterAndRenderManageList(binding.etSearchEmployee.text.toString().trim())
                if (isManageListVisible) loadManageList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}