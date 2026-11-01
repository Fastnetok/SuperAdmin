package com.example.superadmin

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore

class PendingDeviceAdapter(
    private val pendingList: MutableList<PendingDevice>
) : RecyclerView.Adapter<PendingDeviceAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameText: TextView = itemView.findViewById(R.id.nameText)
        val mobileText: TextView = itemView.findViewById(R.id.mobileText)
        val statusText: TextView = itemView.findViewById(R.id.statusText)
        val approveButton: Button = itemView.findViewById(R.id.approveButton)
        val deleteButton: Button = itemView.findViewById(R.id.deleteButton)
        val blockButton: Button = itemView.findViewById(R.id.blockButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pending_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = pendingList[position]

        holder.nameText.text = device.employeeName
        holder.mobileText.text = device.mobileNumber
        holder.statusText.text = device.status

        holder.approveButton.setOnClickListener {
            checkForDuplicateThenApprove(holder, device)
        }

        holder.deleteButton.setOnClickListener {
            val rtdb = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
            rtdb.getReference("PendingDevices").child(device.androidId).removeValue()
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < pendingList.size) {
                pendingList.removeAt(pos)
                notifyItemRemoved(pos)
            }
        }

        holder.blockButton.setOnClickListener {
            val rtdb = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
            rtdb.getReference("PendingDevices").child(device.androidId).child("status").setValue("Blocked")
            holder.statusText.text = "BLOCKED"
        }
    }

    override fun getItemCount(): Int = pendingList.size

    private fun checkForDuplicateThenApprove(
        holder: ViewHolder,
        device: PendingDevice
    ) {
        FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
            .getReference("ApprovedDevices")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var duplicateAndroidId: String? = null

                    for (child in snapshot.children) {
                        val existingName = child.child("employeeName").getValue(String::class.java) ?: ""
                        val existingMobile = child.child("mobileNumber").getValue(String::class.java) ?: ""

                        if (existingName.equals(device.employeeName, ignoreCase = true) &&
                            existingMobile == device.mobileNumber
                        ) {
                            duplicateAndroidId = child.key
                            break
                        }
                    }

                    if (duplicateAndroidId != null) {
                        AlertDialog.Builder(holder.itemView.context)
                            .setTitle("Duplicate Employee")
                            .setMessage(
                                "${device.employeeName} (${device.mobileNumber}) pehle se " +
                                        "ek doosre device (ID: $duplicateAndroidId) mein Approved hai.\n\n" +
                                        "Kya aap phir bhi is naye device ko approve karna chahte hain?"
                            )
                            .setPositiveButton("Haan, Approve karein") { _, _ ->
                                approveDevice(holder, device)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        approveDevice(holder, device)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    approveDevice(holder, device)
                }
            })
    }

    private fun approveDevice(
        holder: ViewHolder,
        device: PendingDevice
    ) {
        val context = holder.itemView.context
        val session = com.example.superadmin.core.SessionManager(context)
        val companyId = session.getCompanyId() ?: session.getActiveCompanyId() ?: ""

        val approvedData = hashMapOf<String, Any>(
            "androidId" to device.androidId,
            "employeeName" to device.employeeName,
            "name" to device.employeeName,
            "mobileNumber" to device.mobileNumber,
            "phoneNumber" to device.mobileNumber,
            "status" to "Approved",
            "uid" to device.uid,
            "companyId" to companyId,
            "company" to companyId,
            "companyName" to companyId
        )

        val keysToSave = mutableSetOf(device.androidId)
        if (device.mobileNumber.isNotBlank()) keysToSave.add(device.mobileNumber)
        if (device.uid.isNotBlank()) keysToSave.add(device.uid)

        val rtdb = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
        val firestore = FirebaseFirestore.getInstance()

        for (k in keysToSave) {
            rtdb.getReference("ApprovedDevices").child(k).setValue(approvedData)
            rtdb.getReference("employees").child(k).setValue(approvedData)
            rtdb.getReference("employeePins").child(k).setValue(approvedData)

            firestore.collection("ApprovedDevices").document(k).set(approvedData)
            firestore.collection("employees").document(k).set(approvedData)
            firestore.collection("employeePins").document(k).set(approvedData)
        }

        rtdb.getReference("PendingDevices").child(device.androidId).removeValue()
        firestore.collection("PendingDevices").document(device.androidId).delete()

        holder.statusText.text = "APPROVED"
    }
}
