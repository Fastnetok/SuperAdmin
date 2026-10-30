package com.example.superadmin.utils

import com.example.superadmin.core.FirestorePaths
import com.google.firebase.firestore.FirebaseFirestore

object CompanyIdGenerator {

    private val db = FirebaseFirestore.getInstance()

    interface IdGeneratedCallback {
        fun onIdGenerated(companyId: String)
        fun onError(message: String)
    }

    fun generateNextId(cityPrefix: String, callback: IdGeneratedCallback) {
        val normalizedPrefix = cityPrefix.trim().uppercase()
        val prefixWithDash = "$normalizedPrefix-"

        db.collection(FirestorePaths.COMPANIES).get()
            .addOnSuccessListener { snapshot ->
                var maxNumber = 0

                for (doc in snapshot.documents) {
                    val compId = (doc.getString("companyId")?.trim() ?: doc.id.trim()).uppercase()
                    if (compId.startsWith(prefixWithDash)) {
                        val numPart = compId.substringAfter(prefixWithDash).takeWhile { it.isDigit() }
                        val num = numPart.toIntOrNull()
                        if (num != null && num > maxNumber) {
                            maxNumber = num
                        }
                    } else if (compId.startsWith(normalizedPrefix)) {
                        val numPart = compId.substringAfter(normalizedPrefix).takeWhile { it.isDigit() }
                        val num = numPart.toIntOrNull()
                        if (num != null && num > maxNumber) {
                            maxNumber = num
                        }
                    }
                }

                val nextNumber = maxNumber + 1
                val paddedNumber = nextNumber.toString().padStart(3, '0')
                val newCompanyId = "$normalizedPrefix-$paddedNumber"

                // Sync counter in COUNTERS collection
                db.collection(FirestorePaths.COUNTERS)
                    .document(normalizedPrefix)
                    .set(mapOf(FirestorePaths.FIELD_LAST_NUMBER to nextNumber))

                callback.onIdGenerated(newCompanyId)
            }
            .addOnFailureListener { e ->
                callback.onError(e.message ?: "Company ID banate waqt error aaya")
            }
    }
}
