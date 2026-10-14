package com.example.superadmin

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.superadmin.core.CompanyDataHelper
import com.google.firebase.database.*
import java.util.Locale

class TotalComplaintsActivity :
    AppCompatActivity() {

    private lateinit var recyclerView:
            RecyclerView

    private lateinit var adapter:
            TotalComplaintAdapter

    private val summaryList =
        mutableListOf<EmployeeComplaintSummary>()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_total_complaints
        )

        recyclerView =
            findViewById(
                R.id.recyclerTotalComplaints
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            TotalComplaintAdapter(
                summaryList
            )

        recyclerView.adapter =
            adapter

        loadEmployeeSummary()

    }

    private fun loadEmployeeSummary() {
        val rtdb = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")

        rtdb.getReference("employees").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(employeeSnapshot: DataSnapshot) {
                rtdb.getReference("complaints").addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(complaintSnapshot: DataSnapshot) {
                        summaryList.clear()
                        val employeeMap = HashMap<String, Int>()

                        for (employee in employeeSnapshot.children) {
                            if (!CompanyDataHelper.belongsToCurrentCompany(this@TotalComplaintsActivity, employee)) continue

                            val employeeName = employee.child("employeeName").getValue(String::class.java)
                                ?.takeIf { it.isNotBlank() }
                                ?: employee.child("name").getValue(String::class.java)
                                ?: continue

                            employeeMap[employeeName] = 0
                        }

                        for (complaint in complaintSnapshot.children) {
                            if (!CompanyDataHelper.belongsToCurrentCompany(this@TotalComplaintsActivity, complaint)) continue
                            val isNew = complaint.child("isNewConnection").getValue(Boolean::class.java) ?: false
                            val assignedTo = complaint.child("assignedTo").getValue(String::class.java) ?: ""

                            if (isNew || assignedTo == "NEW_CONNECTION_DASHBOARD" || assignedTo.isBlank()) continue

                            val status = complaint.child("status").getValue(String::class.java) ?: ""
                            if (status.equals("Resolved", true)) continue

                            val normAssigned = assignedTo.trim().lowercase(Locale.getDefault())
                            val matchingEmp = employeeMap.keys.find { it.trim().lowercase(Locale.getDefault()) == normAssigned }
                            val targetKey = matchingEmp ?: assignedTo

                            employeeMap[targetKey] = (employeeMap[targetKey] ?: 0) + 1
                        }

                        for (entry in employeeMap.entries) {
                            summaryList.add(EmployeeComplaintSummary(entry.key, entry.value))
                        }

                        summaryList.sortByDescending { it.totalComplaints }
                        adapter.notifyDataSetChanged()
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }
}