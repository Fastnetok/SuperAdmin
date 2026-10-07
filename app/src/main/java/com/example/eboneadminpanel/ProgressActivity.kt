package com.example.superadmin

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
        val fb = FirebaseDatabase.getInstance()
        val complaintsRef = fb.getReference("complaints")
        val empComplaintsRef = fb.getReference("employeeComplaints")

        var mainSnap: DataSnapshot? = null
        var empSnap: DataSnapshot? = null

        fun refreshList() {
            val latestComplaintMap = HashMap<String, Complaint>()

            val cSnap = mainSnap
            if (cSnap != null) {
                for (item in cSnap.children) {
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

                        val currentScore = if (complaint.assignedTime > 0) complaint.assignedTime else complaint.createdTime
                        val oldScore = if (oldComplaint != null) (if (oldComplaint.assignedTime > 0) oldComplaint.assignedTime else oldComplaint.createdTime) else 0L

                        if (oldComplaint == null || currentScore > oldScore) {
                            latestComplaintMap[employeeName] = complaint
                        }
                    }
                }
            }

            // Cross-check with employeeComplaints node strictly by complaintId
            val eSnap = empSnap
            if (eSnap != null) {
                for (empNode in eSnap.children) {
                    for (compNode in empNode.children) {
                        val compId = compNode.key ?: continue
                        val (isSeen, seenTime) = checkSnapshotIsSeen(compNode)

                        val compFromSnap = compNode.getValue(Complaint::class.java)

                        // Match STRICTLY by complaintId
                        var matching = latestComplaintMap.values.find { it.complaintId == compId }

                        // If not found in complaints node, construct complaint from employeeComplaints
                        if (matching == null && compFromSnap != null && compFromSnap.assignedTo.isNotBlank() && compFromSnap.complaintId == compId) {
                            if (!compFromSnap.status.equals("Resolved", ignoreCase = true)) {
                                matching = compFromSnap
                                if (matching.complaintId.isBlank()) matching.complaintId = compId
                                val existingInMap = latestComplaintMap[matching.assignedTo]
                                val currentScore = if (matching.assignedTime > 0) matching.assignedTime else matching.createdTime
                                val existingScore = if (existingInMap != null) (if (existingInMap.assignedTime > 0) existingInMap.assignedTime else existingInMap.createdTime) else 0L
                                if (existingInMap == null || currentScore > existingScore) {
                                    latestComplaintMap[matching.assignedTo] = matching
                                }
                            }
                        }

                        // Update seen status on matching complaint if found
                        if (isSeen && matching != null && matching.complaintId == compId) {
                            matching.seenByEmployee = true
                            matching.seenTime = if (seenTime > 0) seenTime else (if (matching.seenTime > 0) matching.seenTime else System.currentTimeMillis())
                        }
                    }
                }
            }

            complaintList.clear()
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
