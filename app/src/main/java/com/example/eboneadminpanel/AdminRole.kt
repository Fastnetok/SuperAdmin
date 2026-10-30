package com.example.superadmin

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

object AdminRole {

    // Fetches the current admin's role ("owner" / "manager" / "supervisor")
    // once and hands it back through the callback. Any screen that needs to
    // hide a button for certain roles should call this in onCreate().
    fun fetch(onResult: (role: String?) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            onResult(null)
            return
        }

        FirebaseFirestore.getInstance()
            .collection("admins")
            .document(uid)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    onResult(document.getString("role") ?: "owner")
                } else {
                    onResult(null)
                }
            }
            .addOnFailureListener {
                onResult(null)
            }
    }

    fun canManageFuel(role: String?): Boolean {
        return role == "owner" || role == "manager"
    }

    fun canManageCompanies(role: String?): Boolean {
        return role == "owner"
    }

    fun canManageGeofences(role: String?): Boolean {
        return role == "owner" || role == "manager"
    }
}