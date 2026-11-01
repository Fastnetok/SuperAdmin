package com.example.superadmin.core

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

object RoleManager {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    const val SUPER_ADMIN_EMAIL = "abbasok@gmail.com"

    interface RoleCheckCallback {
        fun onRoleFound(role: String, companyId: String?)
        fun onError(message: String)
    }

    /**
     * Checks if a target companyId or email belongs to an ACTIVE company in Firestore.
     * Handles case-insensitivity, hyphen variations (e.g. DEP-001 vs dep001), and document ID matching.
     */
    fun verifyCompanyActive(targetCompanyId: String, email: String?, callback: (Boolean, String?) -> Unit) {
        val cleanTarget = targetCompanyId.trim().uppercase()
        val noHyphenTarget = cleanTarget.replace("-", "")

        // 1. Direct lookup by exact document ID
        if (cleanTarget.isNotBlank()) {
            db.collection("companies").document(cleanTarget).get().addOnSuccessListener { compDoc ->
                val status = compDoc.getString("licenseStatus")?.trim()?.uppercase()
                if (compDoc.exists() && (status == "ACTIVE" || status == "DEMO" || status.isNullOrEmpty())) {
                    val canonicalId = compDoc.getString("companyId")?.trim()?.uppercase()
                        ?.ifEmpty { compDoc.id.trim().uppercase() } ?: compDoc.id.trim().uppercase()
                    callback(true, canonicalId)
                    return@addOnSuccessListener
                }
                fallbackCompanySearch(cleanTarget, noHyphenTarget, email, callback)
            }.addOnFailureListener {
                fallbackCompanySearch(cleanTarget, noHyphenTarget, email, callback)
            }
        } else {
            fallbackCompanySearch(cleanTarget, noHyphenTarget, email, callback)
        }
    }

    private fun fallbackCompanySearch(
        cleanTarget: String,
        noHyphenTarget: String,
        email: String?,
        callback: (Boolean, String?) -> Unit
    ) {
        db.collection("companies").get().addOnSuccessListener { snapshot ->
            val cleanEmail = email?.trim()?.lowercase() ?: ""

            val matchedDoc = snapshot.documents.firstOrNull { doc ->
                val docId = doc.id.trim().uppercase()
                val cId = doc.getString("companyId")?.trim()?.uppercase() ?: docId
                val cEmail = doc.getString("adminEmail")?.trim()?.lowercase() ?: ""
                val cStatus = doc.getString("licenseStatus")?.trim()?.uppercase() ?: "ACTIVE"

                val isStatusActive = (cStatus == "ACTIVE" || cStatus == "DEMO")

                val isIdMatch = cleanTarget.isNotBlank() && (
                    docId == cleanTarget || cId == cleanTarget ||
                    docId.replace("-", "") == noHyphenTarget ||
                    cId.replace("-", "") == noHyphenTarget
                )

                val isEmailMatch = cleanEmail.isNotBlank() && cEmail == cleanEmail

                isStatusActive && (isIdMatch || isEmailMatch)
            }

            if (matchedDoc != null) {
                val foundId = matchedDoc.getString("companyId")?.trim()?.uppercase()
                    ?.ifEmpty { matchedDoc.id.trim().uppercase() } ?: matchedDoc.id.trim().uppercase()
                callback(true, foundId)
            } else {
                callback(false, null)
            }
        }.addOnFailureListener {
            callback(false, null)
        }
    }

    fun checkCurrentUserRole(callback: RoleCheckCallback) {
        val user = auth.currentUser
        val uid = user?.uid
        val email = user?.email?.trim()?.lowercase()

        if (uid == null || email.isNullOrBlank()) {
            callback.onError("User logged in nahi hai")
            return
        }

        // 1. Primary Authorized Super Admin Check
        if (email == SUPER_ADMIN_EMAIL || email.contains("abbasok")) {
            db.collection("admins").document(uid).set(
                mapOf("role" to Constants.ROLE_SUPER_ADMIN, "email" to email, "active" to true)
            )
            callback.onRoleFound(Constants.ROLE_SUPER_ADMIN, null)
            return
        }

        // 2. Check explicitly registered admins in 'admins' collection
        db.collection("admins").document(uid).get().addOnSuccessListener { adminDoc ->
            val role = adminDoc.getString("role") ?: Constants.ROLE_COMPANY_ADMIN
            val savedCompanyId = adminDoc.getString("companyId")?.trim()?.uppercase()

            if (adminDoc.exists() && adminDoc.getBoolean("active") != false) {
                if (role == Constants.ROLE_SUPER_ADMIN) {
                    if (email == SUPER_ADMIN_EMAIL || email.contains("abbasok")) {
                        callback.onRoleFound(Constants.ROLE_SUPER_ADMIN, null)
                    } else {
                        db.collection("admins").document(uid).delete()
                        callback.onError("Access Denied: Unregistered or unauthorized account ($email)")
                    }
                    return@addOnSuccessListener
                }

                if (!savedCompanyId.isNullOrBlank()) {
                    verifyCompanyActive(savedCompanyId, email) { isActive, canonicalId ->
                        if (isActive && !canonicalId.isNullOrBlank()) {
                            if (savedCompanyId != canonicalId) {
                                db.collection("admins").document(uid).update("companyId", canonicalId)
                            }
                            callback.onRoleFound(Constants.ROLE_COMPANY_ADMIN, canonicalId)
                        } else {
                            callback.onError("Access Denied: Company deleted or license not active")
                        }
                    }
                    return@addOnSuccessListener
                }
            }

            // 3. Fallback: Search matching active company in 'companies' collection by email
            verifyCompanyActive("", email) { isActive, canonicalId ->
                if (isActive && !canonicalId.isNullOrBlank()) {
                    db.collection("admins").document(uid).set(
                        mapOf(
                            "role" to Constants.ROLE_COMPANY_ADMIN,
                            "companyId" to canonicalId,
                            "email" to email,
                            "active" to true
                        )
                    )
                    callback.onRoleFound(Constants.ROLE_COMPANY_ADMIN, canonicalId)
                } else {
                    callback.onError("Access Denied: Unregistered account ($email)")
                }
            }
        }.addOnFailureListener {
            callback.onError("Access Denied: Admin role check failed")
        }
    }
}
