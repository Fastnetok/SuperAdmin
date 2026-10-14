package com.example.superadmin.core

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.DocumentSnapshot

object CompanyDataHelper {

    fun getCurrentCompanyId(context: Context): String? {
        val sessionManager = SessionManager(context)
        val activeCompany = sessionManager.getActiveCompanyId()
        if (!activeCompany.isNullOrBlank() && activeCompany.uppercase() != "ALL") {
            return activeCompany.trim().uppercase()
        }
        val companyId = sessionManager.getCompanyId()
        return companyId?.trim()?.uppercase()?.takeIf { it.isNotBlank() && it != "ALL" }
    }

    fun isSuperAdmin(context: Context): Boolean {
        return SessionManager(context).isSuperAdmin()
    }

    fun getScopedDatabaseReference(context: Context, nodeName: String): DatabaseReference {
        return FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com").reference.child(nodeName)
    }

    fun belongsToCurrentCompany(context: Context, snapshot: DataSnapshot): Boolean {
        return belongsToCurrentCompany(context, snapshot, null)
    }

    fun belongsToCurrentCompany(context: Context, snapshot: DataSnapshot, parentCompanyId: String?): Boolean {
        val sessionManager = SessionManager(context)
        val isSuper = sessionManager.isSuperAdmin()
        val userCompanyId = sessionManager.getCompanyId()?.trim()?.uppercase()
        val activeCompany = sessionManager.getActiveCompanyId()?.trim()?.uppercase()

        val targetFilter = if (!isSuper) {
            userCompanyId?.takeIf { it.isNotBlank() && it != "ALL" } ?: ""
        } else {
            if (activeCompany.isNullOrEmpty() || activeCompany == "ALL") null else activeCompany
        }

        if (targetFilter.isNullOrEmpty()) {
            return true
        }

        val itemCompanyId = snapshot.child("companyId").getValue(String::class.java)?.trim()?.uppercase().orEmpty()
        val itemCompany = snapshot.child("company").getValue(String::class.java)?.trim()?.uppercase().orEmpty()
        val itemCompanyName = snapshot.child("companyName").getValue(String::class.java)?.trim()?.uppercase().orEmpty()
        val itemCompanyCode = snapshot.child("companyCode").getValue(String::class.java)?.trim()?.uppercase().orEmpty()
        val itemZone = snapshot.child("zone").getValue(String::class.java)?.trim()?.uppercase().orEmpty()

        val allFields = listOf(itemCompanyId, itemCompany, itemCompanyName, itemCompanyCode, itemZone).filter { it.isNotBlank() }
        val cleanTarget = targetFilter.replace("-", "").uppercase()
        val baseTarget = cleanTarget.split(" ", "-", "_").firstOrNull() ?: cleanTarget

        if (allFields.isEmpty()) {
            return cleanTarget.equals("OKARA", ignoreCase = true) || cleanTarget.equals("DEFAULT", ignoreCase = true) || cleanTarget.startsWith("OKA")
        }

        return allFields.any { field ->
            val cleanField = field.replace("-", "").uppercase()
            val baseField = cleanField.split(" ", "-", "_").firstOrNull() ?: cleanField

            cleanField == cleanTarget || 
            baseField == baseTarget ||
            (baseTarget.length >= 3 && cleanField.contains(baseTarget)) ||
            (baseField.length >= 3 && baseTarget.contains(cleanField))
        }
    }

    fun belongsToCurrentCompany(context: Context, doc: DocumentSnapshot): Boolean {
        val sessionManager = SessionManager(context)
        val isSuper = sessionManager.isSuperAdmin()
        val userCompanyId = sessionManager.getCompanyId()?.trim()?.uppercase()
        val activeCompany = sessionManager.getActiveCompanyId()?.trim()?.uppercase()

        val targetFilter = if (!isSuper) {
            userCompanyId?.takeIf { it.isNotBlank() && it != "ALL" } ?: ""
        } else {
            if (activeCompany.isNullOrEmpty() || activeCompany == "ALL") null else activeCompany
        }

        if (targetFilter.isNullOrEmpty()) {
            return true
        }

        val itemCompanyId = doc.getString("companyId")?.trim()?.uppercase().orEmpty()
        val itemCompany = doc.getString("company")?.trim()?.uppercase().orEmpty()
        val itemCompanyName = doc.getString("companyName")?.trim()?.uppercase().orEmpty()
        val itemCompanyCode = doc.getString("companyCode")?.trim()?.uppercase().orEmpty()
        val itemZone = doc.getString("zone")?.trim()?.uppercase().orEmpty()

        val allFields = listOf(itemCompanyId, itemCompany, itemCompanyName, itemCompanyCode, itemZone).filter { it.isNotBlank() }
        val cleanTarget = targetFilter.replace("-", "").uppercase()
        val baseTarget = cleanTarget.split(" ", "-", "_").firstOrNull() ?: cleanTarget

        if (allFields.isEmpty()) {
            return cleanTarget.equals("OKARA", ignoreCase = true) || cleanTarget.equals("DEFAULT", ignoreCase = true) || cleanTarget.startsWith("OKA")
        }

        return allFields.any { field ->
            val cleanField = field.replace("-", "").uppercase()
            val baseField = cleanField.split(" ", "-", "_").firstOrNull() ?: cleanField

            cleanField == cleanTarget || 
            baseField == baseTarget ||
            (baseTarget.length >= 3 && cleanField.contains(baseTarget)) ||
            (baseField.length >= 3 && baseTarget.contains(cleanField))
        }
    }
}
