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
import com.example.superadmin.databinding.ActivityAddEmployeeBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

data class ManageItem(
    val primaryKey: String,
    val name: String,
    val pin: String,
    val role: String,
    val status: String,
    val phone: String,
    val androidId: String?,
    val uid: String?,
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
        val userCompanyId = CompanyDataHelper.getCurrentCompanyId(this) ?: ""

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

        if (userCompanyId.isNotBlank()) {
            val idx = availableCompanies.indexOfFirst { it.id.equals(userCompanyId, ignoreCase = true) || it.name.equals(userCompanyId, ignoreCase = true) }
            if (idx >= 0) {
                binding.spinnerCompany.setSelection(idx)
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

        // Check for existing name within this specific company to avoid duplicates
        empRef.get().addOnSuccessListener { empSnap ->
            pinRef.get().addOnSuccessListener { pinSnap ->
                appRef.get().addOnSuccessListener { appSnap ->
                    var nameExists = false

                    fun isMatching(child: DataSnapshot): Boolean {
                        val name = child.child("employeeName").getValue(String::class.java)
                            ?: child.child("name").getValue(String::class.java)
                        if (name == null || !name.equals(employeeName, ignoreCase = true)) return false

                        val itemCompId = child.child("companyId").getValue(String::class.java)
                            ?: child.child("company").getValue(String::class.java)
                            ?: child.child("companyName").getValue(String::class.java)

                        if (itemCompId.isNullOrEmpty() || companyId == "DEFAULT") return true
                        return itemCompId.equals(companyId, true) || itemCompId.equals(companyName, true) || itemCompId.equals(cleanCompId, true)
                    }

                    for (child in empSnap.children) {
                        if (isMatching(child)) { nameExists = true; break }
                    }
                    if (!nameExists) {
                        for (child in pinSnap.children) {
                            if (isMatching(child)) { nameExists = true; break }
                        }
                    }
                    if (!nameExists) {
                        for (child in appSnap.children) {
                            if (isMatching(child)) { nameExists = true; break }
                        }
                    }

                    if (nameExists) {
                        showResult(
                            "❌ Employee \"$employeeName\" already exists for this company!\n" +
                                    "If their mobile is changed or lost, please use 'Reset PIN' in Manage Employees instead of creating a duplicate entry.",
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
                                "companyCode" to companyId
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

                                        for (child in appSnap.children) parseChild(child, "Approved")
                                        for (child in empSnap.children) parseChild(child, "Approved")
                                        for (child in pinSnap.children) parseChild(child, "PENDING")

                                        for (cSnap in companiesSnap.children) {
                                            val compKey = cSnap.key ?: continue
                                            val cEmps = cSnap.child("employees")
                                            val cPins = cSnap.child("employeePins")
                                            val cApps = cSnap.child("ApprovedDevices")

                                            for (child in cApps.children) parseChild(child, "Approved", compKey)
                                            for (child in cEmps.children) parseChild(child, "Approved", compKey)
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
            val blockBtn = row.findViewById<Button>(R.id.btnRowBlock)
            val deleteBtn = row.findViewById<Button>(R.id.btnRowDelete)

            blockBtn.text = if (item.status.equals("Blocked", true)) "Unblock" else "Block"

            resetPinBtn.setOnClickListener {
                resetEmployeePin(item)
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

    private fun resetEmployeePin(item: ManageItem) {
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

                val approvedData = newPinData.toMutableMap().apply {
                    put("status", "Approved")
                    put("deviceStatus", "Approved")
                    put("appStatus", "Approved")
                }

                val pathsToUpdate = mutableSetOf<String>()
                if (companyId.isNotBlank() && companyId != "DEFAULT") pathsToUpdate.add(companyId)
                if (companyName.isNotBlank() && companyName != "DEFAULT") pathsToUpdate.add(companyName)
                if (cleanCompId.isNotBlank() && cleanCompId != "DEFAULT") pathsToUpdate.add(cleanCompId)

                // Update and unbind all keys associated with this employee name
                for (key in item.allKeys) {
                    db.getReference("employees").child(key).child("linkedAndroidId").removeValue()
                    db.getReference("employees").child(key).child("androidId").removeValue()
                    db.getReference("employees").child(key).child("linkedUid").removeValue()
                    db.getReference("employees").child(key).child("uid").removeValue()

                    db.getReference("employeePins").child(key).child("linkedAndroidId").removeValue()
                    db.getReference("employeePins").child(key).child("androidId").removeValue()
                    db.getReference("employeePins").child(key).child("linkedUid").removeValue()
                    db.getReference("employeePins").child(key).child("uid").removeValue()

                    val updateMap = mapOf<String, Any>(
                        "pin" to newPin,
                        "attendancePin" to newPin,
                        "registrationPin" to newPin,
                        "status" to "PENDING"
                    )
                    db.getReference("employees").child(key).updateChildren(updateMap)
                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("employees").child(key).child("linkedAndroidId").removeValue()
                        db.getReference("companies").child(path).child("employees").child(key).child("androidId").removeValue()
                        db.getReference("companies").child(path).child("employees").child(key).child("linkedUid").removeValue()
                        db.getReference("companies").child(path).child("employees").child(key).child("uid").removeValue()
                        db.getReference("companies").child(path).child("employees").child(key).updateChildren(updateMap)
                    }
                }

                val keysToSet = mutableSetOf(newPin)
                if (item.phone.isNotBlank()) keysToSet.add(item.phone)
                if (cleanPhone.isNotBlank()) keysToSet.add(cleanPhone)
                if (formattedPhone.isNotBlank()) keysToSet.add(formattedPhone)
                if (rawPhoneWithoutZero.isNotBlank()) keysToSet.add(rawPhoneWithoutZero)

                // Write new PIN entry
                for (k in keysToSet) {
                    db.getReference("employeePins").child(k).setValue(newPinData)
                    db.getReference("employees").child(k).setValue(newPinData)
                    db.getReference("ApprovedDevices").child(k).setValue(approvedData)

                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("employeePins").child(k).setValue(newPinData)
                        db.getReference("companies").child(path).child("employees").child(k).setValue(newPinData)
                        db.getReference("companies").child(path).child("ApprovedDevices").child(k).setValue(approvedData)
                    }
                }

                // Clean up old PIN entries in employeePins
                for (key in item.allKeys) {
                    if (key != newPin && !keysToSet.contains(key)) {
                        db.getReference("employeePins").child(key).removeValue()
                        for (path in pathsToUpdate) {
                            db.getReference("companies").child(path).child("employeePins").child(key).removeValue()
                        }
                    }
                }
                if (item.pin.isNotEmpty() && item.pin != newPin && !keysToSet.contains(item.pin)) {
                    db.getReference("employeePins").child(item.pin).removeValue()
                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("employeePins").child(item.pin).removeValue()
                    }
                }

                // Invalidate linked device in ApprovedDevices if exists
                if (!item.androidId.isNullOrBlank()) {
                    db.getReference("ApprovedDevices").child(item.androidId).removeValue()
                    for (path in pathsToUpdate) {
                        db.getReference("companies").child(path).child("ApprovedDevices").child(item.androidId).removeValue()
                    }
                }

                // Sync to Firestore
                val firestore = FirebaseFirestore.getInstance()
                for (k in keysToSet) {
                    firestore.collection("employeePins").document(k).set(newPinData)
                    firestore.collection("employees").document(k).set(newPinData)
                    firestore.collection("ApprovedDevices").document(k).set(approvedData)

                    for (path in pathsToUpdate) {
                        firestore.collection("companies").document(path).collection("employeePins").document(k).set(newPinData)
                        firestore.collection("companies").document(path).collection("employees").document(k).set(newPinData)
                        firestore.collection("companies").document(path).collection("ApprovedDevices").document(k).set(approvedData)
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
                if (!item.androidId.isNullOrBlank()) {
                    db.getReference("ApprovedDevices").child(item.androidId).child("status").setValue(newStatus)
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
                if (!item.androidId.isNullOrBlank()) {
                    db.getReference("ApprovedDevices").child(item.androidId).removeValue()
                    firestore.collection("ApprovedDevices").document(item.androidId).delete()
                    for (path in paths) {
                        db.getReference("companies").child(path).child("ApprovedDevices").child(item.androidId).removeValue()
                        firestore.collection("companies").document(path).collection("ApprovedDevices").document(item.androidId).delete()
                    }
                }
                Toast.makeText(this, "${item.name} deleted from active list", Toast.LENGTH_SHORT).show()
                if (isManageListVisible) loadManageList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}