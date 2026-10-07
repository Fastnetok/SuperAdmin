package com.example.superadmin

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

object FirebaseEmployeeDeduplicator {

    private const val TAG = "EmpDeduplicator"

    /**
     * Inspects Firebase Realtime Database and Firestore for duplicate employee records.
     * Keeps the primary record (e.g. status Approved/ONLINE, non-empty phone, or max fields)
     * and deletes extra duplicate records from Firebase Realtime Database and Firestore.
     */
    fun cleanDuplicateEmployees(onComplete: (() -> Unit)? = null) {
        val db = FirebaseDatabase.getInstance()
        val firestore = FirebaseFirestore.getInstance()

        db.getReference("employees").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(empSnapshot: DataSnapshot) {
                class EmpRecord(
                    val key: String,
                    val name: String,
                    val normName: String,
                    val status: String,
                    val hasPhone: Boolean,
                    val snapshot: DataSnapshot
                )

                val grouped = mutableMapOf<String, MutableList<EmpRecord>>()

                for (child in empSnapshot.children) {
                    val key = child.key ?: continue
                    val rawName = child.child("employeeName").value?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: child.child("name").value?.toString()
                        ?: continue

                    val normName = rawName.trim().lowercase(Locale.getDefault())
                    val statusVal = child.child("status").value?.toString() ?: ""
                    val phone = child.child("phoneNumber").value?.toString()
                        ?: child.child("mobileNumber").value?.toString() ?: ""

                    val rec = EmpRecord(
                        key = key,
                        name = rawName,
                        normName = normName,
                        status = statusVal,
                        hasPhone = phone.isNotBlank(),
                        snapshot = child
                    )

                    grouped.getOrPut(normName) { mutableListOf() }.add(rec)
                }

                for ((normName, list) in grouped) {
                    if (list.size > 1) {
                        Log.d(TAG, "Found ${list.size} duplicate employee records for: $normName")

                        // Sort to pick the best/primary record to keep
                        val sorted = list.sortedWith(
                            compareByDescending<EmpRecord> {
                                it.status.equals("Approved", true) || it.status.equals("ONLINE", true)
                            }.thenByDescending {
                                it.hasPhone
                            }.thenByDescending {
                                it.snapshot.childrenCount
                            }
                        )

                        val primary = sorted.first()
                        val duplicates = sorted.drop(1)

                        for (dup in duplicates) {
                            Log.d(TAG, "Deleting duplicate employee record key=${dup.key} name=${dup.name} (Primary kept key=${primary.key})")

                            // Delete from Realtime Database
                            db.getReference("employees").child(dup.key).removeValue()
                            db.getReference("employeePins").child(dup.key).removeValue()
                            db.getReference("ApprovedDevices").child(dup.key).removeValue()

                            // Delete from Firestore
                            firestore.collection("employees").document(dup.key).delete()
                            firestore.collection("employeePins").document(dup.key).delete()
                            firestore.collection("ApprovedDevices").document(dup.key).delete()
                        }
                    }
                }
                onComplete?.invoke()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error cleaning duplicate employees: ${error.message}")
                onComplete?.invoke()
            }
        })
    }
}
