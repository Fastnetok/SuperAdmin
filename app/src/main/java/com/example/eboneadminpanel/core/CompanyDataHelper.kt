package com.example.superadmin.core

import android.content.Context
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.DocumentSnapshot

object CompanyDataHelper {

    private fun normalizeCompanyId(value: String?): String? {
        return value
            ?.trim()
            ?.uppercase()
            ?.replace("-", "")
            ?.takeIf { it.isNotBlank() && it != "ALL" }
    }

    fun normalizeToCanonical(value: String?): String? {
        val clean = value?.trim()?.uppercase() ?: return null
        return when {
            clean.contains("HAV") -> "HAV"
            clean.contains("OKA") -> "OKA"
            clean.contains("REN") -> "REN"
            clean.contains("DEP") -> "DEP"
            clean.contains("PAK") -> "PAK"
            clean.contains("SAH") -> "SAH"
            clean.contains("ARI") -> "ARI"
            clean.contains("GOJ") -> "GOJ"
            clean.contains("HUJ") -> "HUJ"
            clean.contains("PAT") -> "PAT"
            clean.contains("FSD") -> "FSD"
            clean.contains("LHR") -> "LHR"
            else -> clean.replace("-", "").replace(" ", "")
        }
    }

    fun isSuperAdmin(context: Context): Boolean {
        return SessionManager(context).isSuperAdmin()
    }

    fun getCurrentCompanyId(context: Context): String? {
        val session = SessionManager(context)

        return if (session.isSuperAdmin()) {
            val activeCompany = session.getActiveCompanyId()

            if (activeCompany?.trim()?.equals(
                    "ALL",
                    ignoreCase = true
                ) == true
            ) {
                null
            } else {
                normalizeCompanyId(activeCompany) ?: "OKA001"
            }
        } else {
            normalizeCompanyId(session.getCompanyId()) ?: "OKA001"
        }
    }

    private fun isViewingAllCompanies(context: Context): Boolean {
        val session = SessionManager(context)

        return session.isSuperAdmin() &&
            session.getActiveCompanyId()
                ?.trim()
                ?.equals("ALL", ignoreCase = true) == true
    }

    private fun matchesCompany(
        context: Context,
        snapshot: DataSnapshot,
        parentCompanyId: String?
    ): Boolean {
        if (isViewingAllCompanies(context)) {
            return true
        }

        val targetRaw = getCurrentCompanyId(context) ?: "OKA001"
        val targetCanonical = normalizeToCanonical(targetRaw) ?: "OKA"

        val key = snapshot.key?.trim()?.uppercase() ?: ""
        val itemCompanyId = snapshot.child("companyId").getValue(String::class.java)
            ?: snapshot.child("company").getValue(String::class.java)
            ?: snapshot.child("companyName").getValue(String::class.java)
            ?: snapshot.child("companyCode").getValue(String::class.java)
            ?: snapshot.child("zone").getValue(String::class.java)

        val recordCompanyRaw = itemCompanyId ?: parentCompanyId
        val recordCanonical = normalizeToCanonical(recordCompanyRaw)

        // BRILLIANT PREFIX RULE: Check if employee key/ID starts with franchise prefix (e.g. DEP, OKA, HAV, REN, GOJ, HUJ, PAT)
        val prefixCanonical = when {
            key.startsWith("DEP") -> "DEP"
            key.startsWith("OKA") -> "OKA"
            key.startsWith("HAV") -> "HAV"
            key.startsWith("REN") -> "REN"
            key.startsWith("PAK") -> "PAK"
            key.startsWith("SAH") -> "SAH"
            key.startsWith("ARI") -> "ARI"
            key.startsWith("GOJ") -> "GOJ"
            key.startsWith("HUJ") -> "HUJ"
            key.startsWith("PAT") -> "PAT"
            key.startsWith("FSD") -> "FSD"
            key.startsWith("LHR") -> "LHR"
            else -> null
        }

        val effectiveCanonical = prefixCanonical ?: recordCanonical

        if (effectiveCanonical != null) {
            val matches = effectiveCanonical == targetCanonical
            android.util.Log.d("CompanyMatch", "Prefix/Strict Check: record=${snapshot.key}, prefixCan=$prefixCanonical, recordCan=$recordCanonical, targetCan=$targetCanonical, match=$matches")
            return matches
        }

        // If record has no tags, return false if viewing a specific target company other than default
        return false
    }

    private fun matchesCompany(
        context: Context,
        itemCompanyId: String?
    ): Boolean {
        if (isViewingAllCompanies(context)) {
            return true
        }

        val targetRaw = getCurrentCompanyId(context) ?: "OKA001"
        val targetCanonical = normalizeToCanonical(targetRaw) ?: "OKA"
        val recordCanonical = normalizeToCanonical(itemCompanyId) ?: return false

        return recordCanonical == targetCanonical
    }

    fun getScopedDatabaseReference(
        context: Context,
        nodeName: String
    ): DatabaseReference {
        return FirebaseDatabase.getInstance(
            "https://superadmin-9a853-default-rtdb.firebaseio.com"
        ).reference.child(nodeName)
    }

    fun belongsToCurrentCompany(
        context: Context,
        snapshot: DataSnapshot
    ): Boolean {
        return belongsToCurrentCompany(context, snapshot, null)
    }

    fun belongsToCurrentCompany(
        context: Context,
        snapshot: DataSnapshot,
        parentCompanyId: String?
    ): Boolean {
        return matchesCompany(context, snapshot, parentCompanyId)
    }

    fun belongsToCurrentCompany(
        context: Context,
        doc: DocumentSnapshot
    ): Boolean {
        val itemCompanyId = doc.getString("companyId")
            ?: doc.getString("company")
            ?: doc.getString("companyName")
            ?: doc.getString("companyCode")
            ?: doc.getString("zone")
        return matchesCompany(context, itemCompanyId)
    }

    fun getCanonicalDocId(input: String): String {
        val clean = input.trim().uppercase()

        return when {
            clean.contains("REN") -> "RENALA"
            clean.contains("DEP") -> "DEPALPUR"
            clean.contains("OKA") -> "OKARA"
            clean.contains("PAK") -> "PAKPATTAN"
            clean.contains("SAH") -> "SAHIWAL"
            clean.contains("ARI") -> "ARIFWALA"
            clean.contains("GOJ") -> "GOJRA"
            clean.contains("HUJ") -> "HUJRA"
            clean.contains("PAT") -> "PATTOKI"
            else -> clean
        }
    }

    fun isValidCompanyId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val clean = id.trim().uppercase()
        if (clean == "ALL" || clean == "ALL COMPANIES") return true
        // Exclude personal names or non-standard strings like RANA, NAEEM, plain city names without license numbers
        val invalidNames = setOf("RANA", "NAEEM", "TEST", "ADMIN", "USER", "GOJRA", "PATTOKI", "OKARA", "DEPALPUR", "SAHIWAL", "PAKPATTAN", "ARIFWALA", "RENALA", "HUJRA", "LAHORE")
        if (invalidNames.contains(clean)) return false
        // Valid if it contains a hyphen (e.g. DEP-001) or matches standard prefix + digits (e.g. OKA001, DEP001)
        return clean.contains("-") || clean.matches(Regex("^[A-Z]{3}\\d+$"))
    }
}
