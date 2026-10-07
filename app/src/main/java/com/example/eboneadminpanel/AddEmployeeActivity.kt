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
    private val db = FirebaseDatabase.getInstance()
    private var currentPin: String = ""
    private var isManageListVisible = false
    private var manageListLoaded = false

    private val allManageItems = mutableListOf<ManageItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEmployeeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        generateNewPin()

        val roles = arrayOf("employee", "manager", "supervisor", "dealer")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, roles)
        binding.spinnerRole.adapter = spinnerAdapter

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

        // Check across all nodes for existing name to avoid duplicates
        db.getReference("employees").get().addOnSuccessListener { empSnap ->
            db.getReference("employeePins").get().addOnSuccessListener { pinSnap ->
                db.getReference("ApprovedDevices").get().addOnSuccessListener { appSnap ->
                    var nameExists = false

                    for (child in empSnap.children) {
                        val name = child.child("employeeName").getValue(String::class.java)
                        if (name != null && name.equals(employeeName, ignoreCase = true)) {
                            nameExists = true
                            break
                        }
                    }
                    if (!nameExists) {
                        for (child in pinSnap.children) {
                            val name = child.child("employeeName").getValue(String::class.java)
                            if (name != null && name.equals(employeeName, ignoreCase = true)) {
                                nameExists = true
                                break
                            }
                        }
                    }
                    if (!nameExists) {
                        for (child in appSnap.children) {
                            val name = child.child("employeeName").getValue(String::class.java)
                            if (name != null && name.equals(employeeName, ignoreCase = true)) {
                                nameExists = true
                                break
                            }
                        }
                    }

                    if (nameExists) {
                        showResult(
                            "❌ Employee \"$employeeName\" already exists!\n" +
                                    "If their mobile is changed or lost, please use 'Reset PIN' in Manage Employees instead of creating a duplicate entry.",
                            isError = true
                        )
                        binding.btnSaveEmployee.isEnabled = true
                        return@addOnSuccessListener
                    }

                    db.getReference("employeePins").child(currentPin).get()
                        .addOnSuccessListener { existing ->
                            if (existing.exists()) {
                                showResult("This PIN is already in use — tap Regenerate and try again.", isError = true)
                                binding.btnSaveEmployee.isEnabled = true
                                return@addOnSuccessListener
                            }

                            val selectedRole = binding.spinnerRole.selectedItem.toString().lowercase(Locale.getDefault())
                            val data = mapOf(
                                "employeeName" to employeeName,
                                "phoneNumber" to phoneNumber,
                                "mobileNumber" to phoneNumber,
                                "role" to selectedRole,
                                "status" to "PENDING",
                                "linkedAndroidId" to null,
                                "linkedUid" to null,
                                "createdAt" to System.currentTimeMillis()
                            )

                            // Save to Realtime Database master nodes
                            db.getReference("employees").child(currentPin).setValue(data)
                            db.getReference("employeePins").child(currentPin).setValue(data)
                            db.getReference("ApprovedDevices").child(currentPin).setValue(data)

                            // Save to Firestore collections
                            val firestore = FirebaseFirestore.getInstance()
                            firestore.collection("employees").document(currentPin).set(data)
                            firestore.collection("employeePins").document(currentPin).set(data)
                            firestore.collection("ApprovedDevices").document(currentPin).set(data)

                            showResult(
                                "✅ Employee \"$employeeName\" created!\n\n" +
                                        "Send this to them via WhatsApp:\nName: $employeeName\nPhone: $phoneNumber\nPIN: $currentPin",
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
        db.getReference("employees").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(empSnap: DataSnapshot) {
                db.getReference("ApprovedDevices").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(appSnap: DataSnapshot) {
                        db.getReference("employeePins").addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(pinSnap: DataSnapshot) {

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

                                fun parseChild(child: DataSnapshot, sourceDefaultStatus: String) {
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
                val newPinData = mapOf(
                    "employeeName" to item.name,
                    "phoneNumber" to item.phone,
                    "mobileNumber" to item.phone,
                    "role" to item.role,
                    "status" to "PENDING",
                    "attendancePin" to newPin,
                    "linkedAndroidId" to null,
                    "linkedUid" to null,
                    "createdAt" to System.currentTimeMillis()
                )

                // Update all keys associated with this employee name
                for (key in item.allKeys) {
                    db.getReference("employees").child(key).updateChildren(
                        mapOf(
                            "attendancePin" to newPin,
                            "status" to "PENDING",
                            "linkedAndroidId" to null,
                            "linkedUid" to null
                        )
                    )
                }

                // Write new PIN entry
                db.getReference("employeePins").child(newPin).setValue(newPinData)
                db.getReference("employees").child(newPin).setValue(newPinData)

                // Clean up old PIN entries in employeePins
                for (key in item.allKeys) {
                    if (key != newPin) {
                        db.getReference("employeePins").child(key).removeValue()
                    }
                }
                if (item.pin.isNotEmpty() && item.pin != newPin) {
                    db.getReference("employeePins").child(item.pin).removeValue()
                }

                // Invalidate linked device in ApprovedDevices if exists
                if (!item.androidId.isNullOrBlank()) {
                    db.getReference("ApprovedDevices").child(item.androidId).child("status").setValue("Reset/Pending")
                }

                // Sync to Firestore
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("employeePins").document(newPin).set(newPinData)
                firestore.collection("employees").document(newPin).set(newPinData)
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
                for (key in item.allKeys) {
                    db.getReference("employees").child(key).removeValue()
                    db.getReference("employeePins").child(key).removeValue()
                    db.getReference("ApprovedDevices").child(key).removeValue()
                }
                if (item.pin.isNotEmpty()) {
                    db.getReference("employeePins").child(item.pin).removeValue()
                }
                if (!item.androidId.isNullOrBlank()) {
                    db.getReference("ApprovedDevices").child(item.androidId).removeValue()
                }
                Toast.makeText(this, "${item.name} deleted from active list", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}