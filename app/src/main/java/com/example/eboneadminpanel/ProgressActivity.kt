package com.example.superadmin

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.superadmin.core.CompanyDataHelper
import com.google.firebase.database.*
import java.util.Locale

class ProgressActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ProgressAdapter
    private val complaintList = mutableListOf<Complaint>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_progress)

        recyclerView = findViewById(R.id.recyclerProgress)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = ProgressAdapter(complaintList)
        recyclerView.adapter = adapter

        loadProgressComplaints()
    }

    private fun checkSnapshotIsSeen(snap: DataSnapshot): Pair<Boolean, Long> {
        val seenByEmp = snap.child("seenByEmployee").value
        val seen = snap.child("seen").value
        val read = snap.child("read").value
        val isRead = snap.child("isRead").value
        val readByEmp = snap.child("readByEmployee").value
        val status = snap.child("status").value?.toString()?.uppercase(Locale.getDefault()) ?: ""

        val isSeen = (seenByEmp == true || seenByEmp?.toString().equals("true", ignoreCase = true) || seenByEmp?.toString() == "1") ||
                     (seen == true || seen?.toString().equals("true", ignoreCase = true) || seen?.toString() == "1") ||
                     (read == true || read?.toString().equals("true", ignoreCase = true) || read?.toString() == "1") ||
                     (isRead == true || isRead?.toString().equals("true", ignoreCase = true) || isRead?.toString() == "1") ||
                     (readByEmp == true || readByEmp?.toString().equals("true", ignoreCase = true) || readByEmp?.toString() == "1") ||
                     status == "READ" || status == "SEEN" || status == "VIEWED" || status == "OPENED"

        val rawTime = (snap.child("seenTime").value?.toString()?.toLongOrNull() ?: 0L)
            .let { if (it > 0) it else snap.child("readAt").value?.toString()?.toLongOrNull() ?: 0L }
            .let { if (it > 0) it else snap.child("seenAt").value?.toString()?.toLongOrNull() ?: 0L }
            .let { if (it > 0) it else snap.child("viewedAt").value?.toString()?.toLongOrNull() ?: 0L }

        return Pair(isSeen, rawTime)
    }

    private fun loadProgressComplaints() {
        val fb = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
        val complaintsRef = fb.getReference("complaints")
        val empComplaintsRef = fb.getReference("employeeComplaints")

        var mainSnap: DataSnapshot? = null
        var empSnap: DataSnapshot? = null

        fun refreshList() {
            complaintList.clear()
            val latestComplaintMap = HashMap<String, Complaint>()

            val cSnap = mainSnap
            if (cSnap != null) {
                for (item in cSnap.children) {
                    if (!CompanyDataHelper.belongsToCurrentCompany(this@ProgressActivity, item)) continue
                    val complaint = item.getValue(Complaint::class.java) ?: continue
                    if (complaint.assignedTo.isNotEmpty() && !complaint.status.equals("Resolved", ignoreCase = true)) {
                        val (isSeen, seenTime) = checkSnapshotIsSeen(item)
                        if (isSeen && seenTime > 0) {
                            complaint.seenByEmployee = true
                            complaint.seenTime = seenTime
                        } else if (isSeen) {
                            complaint.seenByEmployee = true
                            complaint.seenTime = System.currentTimeMillis()
                        } else {
                            complaint.seenByEmployee = false
                            complaint.seenTime = 0L
                        }

                        val employeeName = complaint.assignedTo
                        val oldComplaint = latestComplaintMap[employeeName]

                        if (oldComplaint == null || complaint.displayOrder < oldComplaint.displayOrder) {
                            latestComplaintMap[employeeName] = complaint
                        }
                    }
                }
            }

            val eSnap = empSnap
            if (eSnap != null) {
                for (empNode in eSnap.children) {
                    for (compNode in empNode.children) {
                        if (!CompanyDataHelper.belongsToCurrentCompany(this@ProgressActivity, compNode)) continue
                        val compId = compNode.key ?: continue
                        val (isSeen, seenTime) = checkSnapshotIsSeen(compNode)
                        val compFromSnap = compNode.getValue(Complaint::class.java)

                        var matching = latestComplaintMap.values.find { it.complaintId == compId }

                        if (matching == null && compFromSnap != null && compFromSnap.assignedTo.isNotBlank() && compFromSnap.complaintId == compId) {
                            if (!compFromSnap.status.equals("Resolved", ignoreCase = true)) {
                                matching = compFromSnap
                                val existingInMap = latestComplaintMap[matching.assignedTo]
                                if (existingInMap == null || matching.displayOrder < existingInMap.displayOrder) {
                                    latestComplaintMap[matching.assignedTo] = matching
                                }
                            }
                        }

                        if (isSeen && matching != null && matching.complaintId == compId) {
                            matching.seenByEmployee = true
                            if (seenTime > 0) matching.seenTime = seenTime
                        }
                    }
                }
            }

            complaintList.addAll(latestComplaintMap.values)
            adapter.notifyDataSetChanged()
        }

        complaintsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                mainSnap = snapshot
                refreshList()
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        empComplaintsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                empSnap = snapshot
                refreshList()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }
}
