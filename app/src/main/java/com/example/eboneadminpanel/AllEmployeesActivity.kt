package com.example.superadmin

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.Locale

class AllEmployeesActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView

    private val employeeList = mutableListOf<EmployeeItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_all_employees
        )

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadEmployees()
    }

    private fun normalizeName(name: String): String {
        return name.trim().lowercase(Locale.getDefault())
    }

    private fun loadEmployees() {
        val db = FirebaseDatabase.getInstance()

        db.getReference("employees").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(empSnapshot: DataSnapshot) {
                db.getReference("employeePins").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(pinsSnapshot: DataSnapshot) {
                        db.getReference("ApprovedDevices").addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(approvedSnapshot: DataSnapshot) {

                                class RawEmpData(
                                    var primaryKey: String = "",
                                    var name: String = "",
                                    var status: String = "OFFLINE"
                                )

                                val nameToDataMap = mutableMapOf<String, RawEmpData>()

                                fun processSnapshotChild(child: DataSnapshot, sourceDefaultStatus: String) {
                                    val key = child.key ?: return
                                    val rawName = child.child("employeeName").value?.toString()
                                        ?.takeIf { it.isNotBlank() }
                                        ?: child.child("name").value?.toString()
                                        ?: return

                                    val normName = normalizeName(rawName)
                                    val statusVal = child.child("status").value?.toString()
                                        ?.takeIf { it.isNotBlank() }
                                        ?: sourceDefaultStatus

                                    val existing = nameToDataMap.getOrPut(normName) {
                                        RawEmpData(primaryKey = key, name = rawName, status = statusVal)
                                    }

                                    // Prioritize Approved / active / online status over PENDING / OFFLINE
                                    if (statusVal.equals("Approved", true) || statusVal.equals("ONLINE", true)) {
                                        existing.status = statusVal
                                        existing.primaryKey = key
                                    } else if (existing.status.equals("OFFLINE", true) && statusVal.equals("PENDING", true)) {
                                        existing.status = statusVal
                                    }

                                    if (existing.name.isBlank() || existing.name == normName) {
                                        existing.name = rawName
                                    }

                                    // Ensure backfill to Realtime Database employees if missing
                                    if (!empSnapshot.hasChild(key)) {
                                        val backfillData = mapOf(
                                            "employeeName" to rawName,
                                            "phoneNumber" to (child.child("phoneNumber").value?.toString() ?: child.child("mobileNumber").value?.toString() ?: ""),
                                            "mobileNumber" to (child.child("mobileNumber").value?.toString() ?: child.child("phoneNumber").value?.toString() ?: ""),
                                            "status" to statusVal,
                                            "role" to (child.child("role").value?.toString() ?: "employee"),
                                            "createdAt" to System.currentTimeMillis()
                                        )
                                        db.getReference("employees").child(key).updateChildren(backfillData)
                                    }
                                }

                                // 1. ApprovedDevices snapshot
                                for (child in approvedSnapshot.children) {
                                    processSnapshotChild(child, "Approved")
                                }

                                // 2. Realtime Database employees snapshot
                                for (child in empSnapshot.children) {
                                    processSnapshotChild(child, "OFFLINE")
                                }

                                // 3. Realtime Database employeePins snapshot
                                for (child in pinsSnapshot.children) {
                                    processSnapshotChild(child, "PENDING")
                                }

                                employeeList.clear()
                                for ((_, data) in nameToDataMap) {
                                    employeeList.add(
                                        EmployeeItem(
                                            employeeId = data.primaryKey,
                                            name = data.name,
                                            status = data.status
                                        )
                                    )
                                }

                                // Sort alphabetically by employee name
                                employeeList.sortBy { it.name.lowercase(Locale.getDefault()) }

                                if (recyclerView.adapter == null) {
                                    recyclerView.adapter = EmployeeAdapter(employeeList) { employee ->
                                        val intent = Intent(this@AllEmployeesActivity, EmployeeDetailsActivity::class.java)
                                        intent.putExtra("employeeId", employee.employeeId)
                                        startActivity(intent)
                                    }
                                } else {
                                    recyclerView.adapter?.notifyDataSetChanged()
                                }
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
}