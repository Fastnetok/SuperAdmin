package com.example.superadmin

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AddComplaintActivity : AppCompatActivity() {

    private lateinit var repeatManager: RepeatComplaintManager
    private lateinit var userIdInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var phoneInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_complaint)

        repeatManager = RepeatComplaintManager()
        userIdInput = findViewById(R.id.userIdInput)
        addressInput = findViewById(R.id.addressInput)
        phoneInput = findViewById(R.id.phoneInput)

        val commentsInput = findViewById<EditText>(R.id.commentsInput)
        val loginButton = findViewById<Button>(R.id.ocrButton)
        val assignButton = findViewById<Button>(R.id.assignComplaintButton)

        // Selected company is kept only for the current complaint flow.
        var selectedCompany = ""

        loginButton.setOnClickListener {
            val ispList = arrayOf(
                "Ebone (ebill.pk)",
                "Wateen (wateen.com)",
                "Zong (turbonet.zong.com.pk)"
            )

            AlertDialog.Builder(this)
                .setTitle("Select ISP")
                .setItems(ispList) { _, which ->
                    val (selectedISP, selectedZone) = when (which) {
                        0 -> "EBONE" to "Okara"
                        1 -> "WATEEN" to "Okara"
                        else -> "ZONG" to "Okara"
                    }

                    selectedCompany = selectedISP

                    val targetActivity = WebViewRouter.getTargetActivity(selectedISP, selectedZone)
                    val intent = Intent(this, targetActivity).apply {
                        putExtra("selected_isp", selectedISP)
                        putExtra("target_zone", selectedZone)
                        putExtra("zone", selectedZone)

                        // NEW: Pass existing User ID if present so WebView can auto-search
                        val currentId = userIdInput.text.toString().trim()
                        if (currentId.isNotEmpty()) {
                            putExtra("auto_activate_customer_id", currentId)
                        }
                    }

                    webViewLauncher.launch(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        assignButton.setOnClickListener {
            val userId = userIdInput.text.toString().trim()
            val address = addressInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()
            val details = commentsInput.text.toString().trim()

            if (userId.isEmpty()) {
                Toast.makeText(this, "User ID enter karein", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val fb = FirebaseDatabase.getInstance()

            // Step 1: Load all employees & deduplicate strictly by normalized employee name
            fb.getReference("employees").get().addOnSuccessListener { empSnapshot ->
                class UniqueEmp(
                    var displayName: String,
                    var primaryDeviceId: String,
                    val allDeviceIds: MutableSet<String> = mutableSetOf()
                )

                val uniqueEmpMap = mutableMapOf<String, UniqueEmp>()

                for (child in empSnapshot.children) {
                    val rawName = child.child("employeeName").getValue(String::class.java)
                        ?.takeIf { it.isNotBlank() }
                        ?: child.child("name").getValue(String::class.java)
                        ?: continue
                    val deviceId = child.key ?: continue
                    val normName = rawName.trim().lowercase(Locale.getDefault())

                    val statusVal = child.child("status").getValue(String::class.java) ?: ""
                    val existing = uniqueEmpMap.getOrPut(normName) {
                        UniqueEmp(displayName = rawName, primaryDeviceId = deviceId)
                    }

                    existing.allDeviceIds.add(deviceId)

                    if (statusVal.equals("Approved", true) || statusVal.equals("ONLINE", true)) {
                        existing.primaryDeviceId = deviceId
                        existing.displayName = rawName
                    }
                }

                val allEmployees = uniqueEmpMap.values.toList()

                // Step 2: Load attendance to see who is present today
                fb.getReference("attendance").get().addOnSuccessListener { attSnapshot ->
                    val presentDeviceIds = mutableSetOf<String>()
                    for (empAtt in attSnapshot.children) {
                        if (empAtt.hasChild(todayKey)) {
                            presentDeviceIds.add(empAtt.key ?: "")
                        }
                    }

                    // Step 3: Load complaints to count current workload
                    fb.getReference("complaints").get().addOnSuccessListener { compSnapshot ->
                        val complaintCounts = mutableMapOf<String, Int>() // Normalized Name -> Count
                        for (comp in compSnapshot.children) {
                            val assignedTo = comp.child("assignedTo").getValue(String::class.java) ?: ""
                            val status = comp.child("status").getValue(String::class.java) ?: ""
                            if (assignedTo.isNotEmpty() && status != "Resolved") {
                                val normAssigned = assignedTo.trim().lowercase(Locale.getDefault())
                                complaintCounts[normAssigned] = (complaintCounts[normAssigned] ?: 0) + 1
                            }
                        }

                        // Step 4: Prepare display list with Online (Present) on top, Offline (Absent) below
                        val filteredList = mutableListOf<String>()
                        val finalEmployeeNames = mutableListOf<String>()

                        val presentEmployees = mutableListOf<UniqueEmp>()
                        val absentEmployees = mutableListOf<UniqueEmp>()

                        for (emp in allEmployees) {
                            val isPresent = emp.allDeviceIds.any { presentDeviceIds.contains(it) }
                            if (isPresent) {
                                presentEmployees.add(emp)
                            } else {
                                absentEmployees.add(emp)
                            }
                        }

                        presentEmployees.sortByDescending { complaintCounts[it.displayName.trim().lowercase(Locale.getDefault())] ?: 0 }
                        absentEmployees.sortByDescending { complaintCounts[it.displayName.trim().lowercase(Locale.getDefault())] ?: 0 }

                        val addedNorms = HashSet<String>()
                        for (emp in presentEmployees) {
                            val norm = emp.displayName.trim().lowercase(Locale.getDefault())
                            if (addedNorms.add(norm)) {
                                val count = complaintCounts[norm] ?: 0
                                finalEmployeeNames.add(emp.displayName)
                                filteredList.add("🟢 ${emp.displayName} (Online | Active: $count)")
                            }
                        }

                        for (emp in absentEmployees) {
                            val norm = emp.displayName.trim().lowercase(Locale.getDefault())
                            if (addedNorms.add(norm)) {
                                val count = complaintCounts[norm] ?: 0
                                finalEmployeeNames.add(emp.displayName)
                                filteredList.add("🔴 ${emp.displayName} (Offline | Active: $count)")
                            }
                        }

                        val totalCount = finalEmployeeNames.size

                        if (finalEmployeeNames.isEmpty()) {
                            Toast.makeText(this, "Koi employee registered nahi hai", Toast.LENGTH_LONG).show()
                            return@addOnSuccessListener
                        }

                        // Step 5: Show the list in a dialog with total count
                        AlertDialog.Builder(this)
                            .setTitle("Select Employee (Total: $totalCount)")
                            .setItems(filteredList.toTypedArray()) { _, which ->
                                val selectedEmployee = finalEmployeeNames[which]
                                val currentTime = System.currentTimeMillis()

                                // Normal complaints assignment logic
                                repeatManager.checkRepeatComplaint(userId) { repeatInfo ->
                                    if (repeatInfo.isRepeat) {
                                        val resolveDate = if (repeatInfo.lastResolvedTime > 0) {
                                            SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date(repeatInfo.lastResolvedTime))
                                        } else { "Unknown" }
                                        Toast.makeText(this, "⚠️ Repeat Complaint\nResolver: ${repeatInfo.lastResolver}\nLast Resolved: $resolveDate\nCount: ${repeatInfo.repeatCount}", Toast.LENGTH_LONG).show()
                                    }

                                    val complaintId = fb.getReference("complaints").push().key ?: return@checkRepeatComplaint

                                    val complaint = Complaint(
                                        complaintId = complaintId,
                                        userId = userId,
                                        address = address,
                                        phoneNumber = phone,
                                        details = details,
                                        company = selectedCompany,
                                        status = "Progress",
                                        assignedTo = selectedEmployee,
                                        assignedTime = currentTime,
                                        createdTime = currentTime
                                    )

                                    fb.getReference("complaints").child(complaintId).setValue(complaint).addOnSuccessListener {
                                        if (selectedCompany.isNotBlank()) {
                                            fb.getReference("companies").child(selectedCompany.uppercase(Locale.getDefault())).child("complaints").child(complaintId).setValue(complaint)
                                        }
                                        val normEmp = selectedEmployee.trim().lowercase(Locale.getDefault())
                                        fb.getReference("employeeComplaints").child(selectedEmployee).child(complaintId).setValue(complaint)
                                        fb.getReference("employeeComplaints").child(normEmp).child(complaintId).setValue(complaint)

                                        val notifData = hashMapOf(
                                            "title" to "New Complaint Assigned",
                                            "message" to "User ID: $userId",
                                            "complaintId" to complaintId,
                                            "timestamp" to System.currentTimeMillis()
                                        )
                                        fb.getReference("employeeNotifications").child(selectedEmployee).push().setValue(notifData)
                                        if (normEmp != selectedEmployee) {
                                            fb.getReference("employeeNotifications").child(normEmp).push().setValue(notifData)
                                        }
                                        Toast.makeText(this, "Complaint assigned to $selectedEmployee", Toast.LENGTH_SHORT).show()
                                        finish()
                                    }.addOnFailureListener {
                                        Toast.makeText(this, "Failed to save complaint", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }
    }

    private val webViewLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data

                val fetchedUserId =
                    data?.getStringExtra("fetched_user_id") ?: ""

                val fetchedAddress =
                    data?.getStringExtra("fetched_address") ?: ""

                val fetchedPhone =
                    data?.getStringExtra("fetched_phone") ?: ""

                if (fetchedUserId.isNotEmpty()) {
                    userIdInput.setText(fetchedUserId)
                }

                if (fetchedAddress.isNotEmpty()) {
                    addressInput.setText(fetchedAddress)
                }

                if (fetchedPhone.isNotEmpty()) {
                    phoneInput.setText(fetchedPhone)
                }
            }
        }
}
