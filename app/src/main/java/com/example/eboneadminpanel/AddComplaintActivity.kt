package com.example.superadmin

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.superadmin.CityPermissionManager
import com.example.superadmin.core.CompanyDataHelper
import com.google.firebase.database.DataSnapshot
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

        var selectedCompany = ""

        loginButton.setOnClickListener {
            val currentZone = CompanyDataHelper.getCurrentCompanyId(this) ?: "OKARA"
            CityPermissionManager.isCompanyEnabled(this, currentZone, "EBONE") { eboneEnabled ->
                CityPermissionManager.isCompanyEnabled(this, currentZone, "WATEEN") { wateenEnabled ->
                    CityPermissionManager.isCompanyEnabled(this, currentZone, "ZONG") { zongEnabled ->
                        CityPermissionManager.isCompanyEnabled(this, currentZone, "JAZZ") { jazzEnabled ->
                            val list = mutableListOf<String>()
                            val ispMappings = mutableListOf<Pair<String, String>>()
                            if (eboneEnabled) {
                                list.add("Ebone (ebill.pk)")
                                ispMappings.add("EBONE" to "Okara")
                            }
                            if (wateenEnabled) {
                                list.add("Wateen (wateen.com)")
                                ispMappings.add("WATEEN" to "Okara")
                            }
                            if (zongEnabled) {
                                list.add("Zong (turbonet.zong.com.pk)")
                                ispMappings.add("ZONG" to "Okara")
                            }
                            if (jazzEnabled) {
                                list.add("Jazz (biz.jazzftth.pk)")
                                ispMappings.add("JAZZ" to "Okara")
                            }

                            if (list.isEmpty()) {
                                Toast.makeText(this, "No ISP enabled for this company", Toast.LENGTH_SHORT).show()
                                return@isCompanyEnabled
                            }

                            AlertDialog.Builder(this)
                                .setTitle("Select ISP")
                                .setItems(list.toTypedArray()) { _, which ->
                                    val (selectedISP, selectedZone) = ispMappings[which]
                                    selectedCompany = selectedISP

                                    val targetActivity = WebViewRouter.getTargetActivity(selectedISP, selectedZone)
                                    val intent = Intent(this, targetActivity).apply {
                                        putExtra("selected_isp", selectedISP)
                                        putExtra("target_zone", selectedZone)
                                        putExtra("zone", selectedZone)

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
                    }
                }
            }
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
            val fb = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")

            // Step 1: Load all employees across Root and Company nodes
            fb.getReference("employees").get().addOnSuccessListener { empSnapshot ->
                fb.getReference("employeePins").get().addOnSuccessListener { pinSnapshot ->
                    fb.getReference("ApprovedDevices").get().addOnSuccessListener { appSnapshot ->
                        fb.getReference("companies").get().addOnSuccessListener { companiesSnapshot ->

                            class UniqueEmp(
                                var displayName: String,
                                var primaryDeviceId: String,
                                var isOnlineStatus: Boolean = false,
                                val allDeviceIds: MutableSet<String> = mutableSetOf()
                            )

                            val uniqueEmpMap = mutableMapOf<String, UniqueEmp>()

                            fun processChild(child: DataSnapshot, parentCompanyId: String? = null) {
                                if (!CompanyDataHelper.belongsToCurrentCompany(this@AddComplaintActivity, child, parentCompanyId)) {
                                    return
                                }
                                val rawName = child.child("employeeName").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                                    ?: child.child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                                    ?: child.child("empName").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                                    ?: child.child("fullName").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                                    ?: return
                                val deviceId = child.key ?: return
                                val normName = rawName.trim().lowercase(Locale.getDefault())

                                val statusVal = child.child("status").getValue(String::class.java)?.uppercase(Locale.getDefault()) ?: ""
                                val isOnlineFlag = child.child("isOnline").value == true || child.child("online").value == true

                                val existing = uniqueEmpMap.getOrPut(normName) {
                                    UniqueEmp(displayName = rawName, primaryDeviceId = deviceId)
                                }

                                existing.allDeviceIds.add(deviceId)
                                existing.allDeviceIds.add(rawName)
                                existing.allDeviceIds.add(normName)

                                val phone = child.child("phoneNumber").value?.toString() ?: child.child("mobileNumber").value?.toString() ?: ""
                                val cleanPhone = phone.replace("+", "").replace("-", "").replace(" ", "").trim()
                                if (phone.isNotBlank()) existing.allDeviceIds.add(phone)
                                if (cleanPhone.isNotBlank()) existing.allDeviceIds.add(cleanPhone)

                                val pin = child.child("attendancePin").value?.toString() ?: child.child("pin").value?.toString() ?: ""
                                if (pin.isNotBlank()) existing.allDeviceIds.add(pin)

                                if (statusVal == "APPROVED" || statusVal == "ONLINE" || statusVal == "CHECKED_IN" || statusVal == "CHECKEDIN" || isOnlineFlag) {
                                    existing.primaryDeviceId = deviceId
                                    existing.displayName = rawName
                                    existing.isOnlineStatus = true
                                }
                            }

                            for (c in empSnapshot.children) processChild(c)
                            for (c in pinSnapshot.children) processChild(c)
                            for (c in appSnapshot.children) processChild(c)

                            for (cSnap in companiesSnapshot.children) {
                                val compKey = cSnap.key ?: ""
                                for (c in cSnap.child("employees").children) processChild(c, compKey)
                                for (c in cSnap.child("employeePins").children) processChild(c, compKey)
                                for (c in cSnap.child("ApprovedDevices").children) processChild(c, compKey)
                            }

                            val allEmployees = uniqueEmpMap.values.toList()

                // Step 2: Load attendance to see who is present today
                fb.getReference("attendance").get().addOnSuccessListener { attSnapshot ->
                    val presentDeviceIds = mutableSetOf<String>()
                    for (empAtt in attSnapshot.children) {
                        val key = empAtt.key ?: ""
                        if (key.isNotBlank()) presentDeviceIds.add(key)
                        if (empAtt.hasChild(todayKey) || empAtt.childrenCount > 0L) {
                            if (key.isNotBlank()) presentDeviceIds.add(key)
                        }
                        for (dateChild in empAtt.children) {
                            if (dateChild.key == todayKey) {
                                if (key.isNotBlank()) presentDeviceIds.add(key)
                            }
                        }
                    }

                    // Step 3: Load complaints to count current workload
                    fb.getReference("complaints").get().addOnSuccessListener { compSnapshot ->
                        val complaintCounts = mutableMapOf<String, Int>()
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
                            val isPresent = emp.isOnlineStatus || emp.allDeviceIds.any { presentDeviceIds.contains(it) }
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

                                repeatManager.checkRepeatComplaint(userId) { repeatInfo ->
                                    if (repeatInfo.isRepeat) {
                                        val resolveDate = if (repeatInfo.lastResolvedTime > 0) {
                                            SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date(repeatInfo.lastResolvedTime))
                                        } else { "Unknown" }
                                        Toast.makeText(this, "⚠️ Repeat Complaint\nResolver: ${repeatInfo.lastResolver}\nLast Resolved: $resolveDate\nCount: ${repeatInfo.repeatCount}", Toast.LENGTH_LONG).show()
                                    }

                                    val complaintId = fb.getReference("complaints").push().key ?: return@checkRepeatComplaint

                                    val currentCompanyId = CompanyDataHelper.getCurrentCompanyId(this) ?: ""
                                    val complaint = Complaint(
                                        complaintId = complaintId,
                                        userId = userId,
                                        address = address,
                                        phoneNumber = phone,
                                        details = details,
                                        company = if (selectedCompany.isNotBlank()) selectedCompany else currentCompanyId,
                                        companyId = currentCompanyId,
                                        status = "Progress",
                                        assignedTo = selectedEmployee,
                                        assignedTime = currentTime,
                                        createdTime = currentTime
                                    )

                                    fb.getReference("complaints").child(complaintId).setValue(complaint).addOnSuccessListener {
                                        if (currentCompanyId.isNotBlank()) {
                                            fb.getReference("companies").child(currentCompanyId).child("complaints").child(complaintId).setValue(complaint)
                                        }
                                        if (selectedCompany.isNotBlank() && selectedCompany.uppercase(Locale.getDefault()) != currentCompanyId) {
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
