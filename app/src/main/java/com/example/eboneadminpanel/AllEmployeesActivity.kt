package com.example.superadmin

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.superadmin.core.CompanyDataHelper
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
        val db = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")

        db.getReference("employees").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(empSnapshot: DataSnapshot) {
                db.getReference("employeePins").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(pinsSnapshot: DataSnapshot) {
                        db.getReference("ApprovedDevices").addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(approvedSnapshot: DataSnapshot) {
                                db.getReference("companies").addListenerForSingleValueEvent(object : ValueEventListener {
                                    override fun onDataChange(companiesSnapshot: DataSnapshot) {

                                        val activeCompanyIds = HashSet<String>()
                                        for (cSnap in companiesSnapshot.children) {
                                            val cId = cSnap.key?.trim()?.uppercase()
                                            if (!cId.isNullOrBlank()) activeCompanyIds.add(cId)
                                            val internalId = cSnap.child("companyId").getValue(String::class.java)?.trim()?.uppercase()
                                            if (!internalId.isNullOrBlank()) activeCompanyIds.add(internalId)
                                        }

                                        if (companiesSnapshot.childrenCount == 0L) {
                                            employeeList.clear()
                                            if (recyclerView.adapter == null) {
                                                recyclerView.adapter = EmployeeAdapter(employeeList) { }
                                            } else {
                                                recyclerView.adapter?.notifyDataSetChanged()
                                            }
                                            return
                                        }

                                        class RawEmpData(
                                            var primaryKey: String = "",
                                            var name: String = "",
                                            var status: String = "OFFLINE"
                                        )

                                        val nameToDataMap = mutableMapOf<String, RawEmpData>()

                                        fun processSnapshotChild(child: DataSnapshot, sourceDefaultStatus: String, parentCompanyId: String? = null) {
                                            if (!CompanyDataHelper.belongsToCurrentCompany(this@AllEmployeesActivity, child, parentCompanyId)) return
                                            val key = child.key ?: return

                                            val itemCompanyId = child.child("companyId").getValue(String::class.java)?.trim()?.uppercase()
                                                ?: child.child("company").getValue(String::class.java)?.trim()?.uppercase()
                                                ?: child.child("companyCode").getValue(String::class.java)?.trim()?.uppercase()
                                                ?: parentCompanyId

                                            if (activeCompanyIds.isNotEmpty() && (itemCompanyId.isNullOrBlank() || !activeCompanyIds.contains(itemCompanyId))) {
                                                var foundInSubNode = false
                                                for (cSnap in companiesSnapshot.children) {
                                                    if (cSnap.child("employees").hasChild(key) || cSnap.child("ApprovedDevices").hasChild(key) || cSnap.child("employeePins").hasChild(key)) {
                                                        foundInSubNode = true
                                                        break
                                                    }
                                                }
                                                if (!foundInSubNode) return
                                            }

                                            val rawName = child.child("employeeName").value?.toString()
                                                ?.takeIf { it.isNotBlank() }
                                                ?: child.child("name").value?.toString()
                                                ?: child.child("empName").value?.toString()
                                                ?: child.child("fullName").value?.toString()
                                                ?: return

                                            val normName = normalizeName(rawName)
                                            val compTag = itemCompanyId ?: parentCompanyId ?: "GLOBAL"
                                            val empMapKey = "${compTag}_${normName}"
                                            val statusVal = child.child("status").value?.toString()
                                                ?.takeIf { it.isNotBlank() }
                                                ?: sourceDefaultStatus

                                            val existing = nameToDataMap.getOrPut(empMapKey) {
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
                                        }

                                        // 1. ApprovedDevices snapshot
                                        for (child in approvedSnapshot.children) processSnapshotChild(child, "Approved")

                                        // 2. Realtime Database employees snapshot
                                        for (child in empSnapshot.children) processSnapshotChild(child, "OFFLINE")

                                        // 3. Realtime Database employeePins snapshot
                                        for (child in pinsSnapshot.children) processSnapshotChild(child, "PENDING")

                                        // 4. Company Sub-nodes
                                        for (cSnap in companiesSnapshot.children) {
                                            val compKey = cSnap.key ?: ""
                                            for (child in cSnap.child("ApprovedDevices").children) processSnapshotChild(child, "Approved", compKey)
                                            for (child in cSnap.child("employees").children) processSnapshotChild(child, "OFFLINE", compKey)
                                            for (child in cSnap.child("employeePins").children) processSnapshotChild(child, "PENDING", compKey)
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

            override fun onCancelled(error: DatabaseError) {}
        })
    }
}