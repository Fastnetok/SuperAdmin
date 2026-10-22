package com.example.superadmin.repositories

import com.example.superadmin.AppFeatures
import com.example.superadmin.core.CompanyDataHelper
import com.example.superadmin.core.FirestorePaths
import com.example.superadmin.models.Company
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class CompanyRepository {

    private val db = FirebaseFirestore.getInstance()
    private val companiesRef = db.collection(FirestorePaths.COMPANIES)

    interface CompanyListCallback {
        fun onSuccess(companies: List<Company>)
        fun onError(message: String)
    }

    interface SimpleCallback {
        fun onSuccess()
        fun onError(message: String)
    }

    interface EmailCheckCallback {
        fun onResult(exists: Boolean)
        fun onError(message: String)
    }

    fun checkEmailExists(email: String, excludeCompanyId: String?, callback: EmailCheckCallback) {
        val clean = email.trim().lowercase()
        if (clean.isBlank()) {
            callback.onResult(false)
            return
        }

        companiesRef.get()
            .addOnSuccessListener { snapshot ->
                var exists = false
                for (doc in snapshot.documents) {
                    val comp = doc.toObject(Company::class.java)
                    val existingEmail = comp?.adminEmail?.trim()?.lowercase().orEmpty()
                    val existingId = comp?.companyId?.trim()?.uppercase() ?: doc.id.trim().uppercase()

                    if (existingEmail == clean) {
                        if (excludeCompanyId == null || existingId != excludeCompanyId.trim().uppercase()) {
                            exists = true
                            break
                        }
                    }
                }
                callback.onResult(exists)
            }
            .addOnFailureListener { e ->
                callback.onError(e.message ?: "Email check karne mein ghalti hui")
            }
    }

    fun addCompany(company: Company, callback: SimpleCallback) {
        val canonicalId = company.companyId.trim()
        if (canonicalId.isBlank()) {
            callback.onError("Company ID blank hai")
            return
        }

        companiesRef.document(canonicalId).set(company)
            .addOnSuccessListener {
                val rtdb = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com").getReference("companies")
                rtdb.child(canonicalId).setValue(company)

                // 1. Initialize franchiseSettings with default disabled switches
                val docId = CompanyDataHelper.getCanonicalDocId(company.companyId)
                val cityDocId = CompanyDataHelper.getCanonicalDocId(company.city)
                val companiesMap = AppFeatures.ALL_COMPANIES.associateWith { false }
                val featuresMap = AppFeatures.ALL_FEATURES.associateWith { feat ->
                    val clean = feat.trim().lowercase()
                    if (clean.contains("movement tracking") || clean.contains("auto complaint") || clean.contains("webview")) {
                        false
                    } else {
                        true
                    }
                }
                val settingsData = mapOf(
                    "enabledCompanies" to companiesMap,
                    "enabledFeatures" to featuresMap,
                    "cityName" to company.city
                )
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("franchiseSettings").document(docId).set(settingsData, SetOptions.merge())
                firestore.collection("franchiseSettings").document(cityDocId).set(settingsData, SetOptions.merge())

                // 2. Automatically provision Firebase Auth account & admin record so company admin login works 100% seamlessly
                val email = company.adminEmail.trim().lowercase()
                val password = company.adminPassword.trim().ifEmpty { "12345678" }
                if (email.isNotBlank()) {
                    val auth = FirebaseAuth.getInstance()
                    auth.createUserWithEmailAndPassword(email, password)
                        .addOnSuccessListener { result ->
                            val uid = result.user?.uid
                            if (uid != null) {
                                firestore.collection("admins").document(uid).set(
                                    mapOf(
                                        "role" to "company_admin",
                                        "companyId" to company.companyId,
                                        "companyName" to company.companyName,
                                        "email" to email,
                                        "active" to true
                                    )
                                ).addOnCompleteListener {
                                    callback.onSuccess()
                                }
                            } else {
                                callback.onSuccess()
                            }
                        }
                        .addOnFailureListener {
                            auth.signInWithEmailAndPassword(email, password)
                                .addOnSuccessListener { signInResult ->
                                    val uid = signInResult.user?.uid
                                    if (uid != null) {
                                        firestore.collection("admins").document(uid).set(
                                            mapOf(
                                                "role" to "company_admin",
                                                "companyId" to company.companyId,
                                                "companyName" to company.companyName,
                                                "email" to email,
                                                "active" to true
                                            )
                                        ).addOnCompleteListener {
                                            callback.onSuccess()
                                        }
                                    } else {
                                        callback.onSuccess()
                                    }
                                }
                                .addOnFailureListener {
                                    callback.onSuccess()
                                }
                        }
                } else {
                    callback.onSuccess()
                }
            }
            .addOnFailureListener { e -> callback.onError(e.message ?: "Company add nahi ho saki") }
    }

    fun getAllCompanies(callback: CompanyListCallback) {
        companiesRef.get()
            .addOnSuccessListener { snapshot ->
                val rawCompanies = snapshot.documents.mapNotNull { doc ->
                    val company = doc.toObject(Company::class.java)
                    if (company != null && company.companyId.isBlank()) {
                        company.companyId = doc.id
                    }
                    company
                }

                val uniqueCompanies = rawCompanies
                    .filter { it.companyId.isNotBlank() }
                    .distinctBy { it.companyId.trim().uppercase() }

                if (uniqueCompanies.isEmpty()) {
                    purgeAllRegistrationData(null)
                }

                callback.onSuccess(uniqueCompanies)
            }
            .addOnFailureListener { e -> callback.onError(e.message ?: "Companies load nahi hui") }
    }

    fun updateCompany(company: Company, callback: SimpleCallback) {
        val canonicalId = company.companyId.trim()
        if (canonicalId.isBlank()) {
            callback.onError("Company ID blank hai")
            return
        }

        companiesRef.document(canonicalId).set(company)
            .addOnSuccessListener {
                val rtdb = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com").getReference("companies")
                rtdb.child(canonicalId).setValue(company)
                callback.onSuccess()
            }
            .addOnFailureListener { e -> callback.onError(e.message ?: "Company update nahi ho saki") }
    }

    fun deleteCompany(companyId: String, callback: SimpleCallback) {
        val canonicalId = companyId.trim()
        if (canonicalId.isBlank()) {
            callback.onError("Company ID blank hai")
            return
        }

        companiesRef.document(canonicalId).delete()
            .addOnSuccessListener {
                val rtdb = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com").getReference("companies")
                rtdb.child(canonicalId).removeValue()

                val docId = CompanyDataHelper.getCanonicalDocId(canonicalId)
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("franchiseSettings").document(docId).delete()

                callback.onSuccess()
            }
            .addOnFailureListener { e -> callback.onError(e.message ?: "Company delete nahi ho saki") }
    }

    fun cleanOrphanedData() {
        companiesRef.get().addOnSuccessListener { companySnap ->
            val activeCompanyIds = mutableSetOf<String>()
            for (doc in companySnap.documents) {
                activeCompanyIds.add(doc.id.trim().uppercase())
                doc.getString("companyId")?.let { activeCompanyIds.add(it.trim().uppercase()) }
                doc.getString("companyCode")?.let { activeCompanyIds.add(it.trim().uppercase()) }
                doc.getString("companyName")?.let { activeCompanyIds.add(it.trim().uppercase()) }
            }

            val rtdbRef = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com").getReference()
            val masterNodes = listOf("employees", "ApprovedDevices", "employeePins", "attendance", "tracking")
            for (node in masterNodes) {
                rtdbRef.child(node).get().addOnSuccessListener { nodeSnap ->
                    for (child in nodeSnap.children) {
                        val compId = child.child("companyId").value?.toString() 
                            ?: child.child("companyCode").value?.toString() 
                            ?: child.child("companyName").value?.toString() 
                            ?: child.child("company").value?.toString() ?: ""
                        
                        val cNorm = compId.trim().uppercase()

                        val isOrphaned = cNorm.isBlank() || (activeCompanyIds.isNotEmpty() && !activeCompanyIds.contains(cNorm) && !activeCompanyIds.any { cNorm.contains(it) })

                        if (isOrphaned) {
                            child.ref.removeValue()
                        }
                    }
                }
            }

            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("dealers").get().addOnSuccessListener { dealerSnap ->
                val batch = firestore.batch()
                var hasDeletes = false
                for (doc in dealerSnap.documents) {
                    val dComp = doc.getString("companyId") ?: doc.getString("company") ?: doc.getString("zone") ?: ""
                    val dNorm = dComp.trim().uppercase()
                    if (dNorm.isNotBlank() && activeCompanyIds.isNotEmpty() && !activeCompanyIds.contains(dNorm) && !activeCompanyIds.any { dNorm.contains(it) }) {
                        batch.delete(doc.reference)
                        hasDeletes = true
                    }
                }
                if (hasDeletes) {
                    batch.commit()
                }
            }
        }
    }

    private fun isMatchingTargetCompany(child: com.google.firebase.database.DataSnapshot, targetId: String): Boolean {
        val targetCanonical = CompanyDataHelper.normalizeToCanonical(targetId) ?: targetId.replace("-", "").uppercase()
        val cleanTarget = targetId.replace("-", "").uppercase()
        val key = child.key?.trim()?.uppercase() ?: ""

        // 1. Check if key starts with prefix or canonical ID or clean target ID (e.g. GOJ, GOJ001, GOJ-01)
        if (key.startsWith(targetCanonical) || key.startsWith(cleanTarget) ||
            (targetCanonical.length >= 3 && key.startsWith(targetCanonical.take(3)))) {
            return true
        }

        // 2. Check all possible company identifier tags in the child snapshot
        val compVal = child.child("companyId").value?.toString()
            ?: child.child("companyCode").value?.toString()
            ?: child.child("companyName").value?.toString()
            ?: child.child("company").value?.toString()
            ?: child.child("zone").value?.toString()
            ?: ""

        if (compVal.isNotBlank()) {
            val recordCanonical = CompanyDataHelper.normalizeToCanonical(compVal) ?: compVal.replace("-", "").uppercase()
            val cleanCompVal = compVal.replace("-", "").uppercase()
            if (recordCanonical == targetCanonical || cleanCompVal == cleanTarget ||
                recordCanonical.contains(targetCanonical) || targetCanonical.contains(recordCanonical)) {
                return true
            }
        }

        return false
    }

    fun resetCompanyTestData(companyId: String, callback: SimpleCallback) {
        val targetId = companyId.trim()
        if (targetId.isBlank()) {
            callback.onError("Company ID invalid hai")
            return
        }
        val canonicalId = CompanyDataHelper.normalizeToCanonical(targetId) ?: targetId.replace("-", "").uppercase()
        val cleanId = targetId.replace("-", "").uppercase()

        purgeTargetRegistrationData(targetId, object : SimpleCallback {
            override fun onSuccess() {
                val rtdbRef = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com").getReference("companies")
                
                rtdbRef.child(targetId).removeValue()
                rtdbRef.child(canonicalId).removeValue()
                rtdbRef.child(cleanId).removeValue()

                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("admins").get().addOnSuccessListener { snaps ->
                    val batch = firestore.batch()
                    var hasBatch = false
                    for (doc in snaps) {
                        val docCompId = doc.getString("companyId") ?: doc.getString("company") ?: ""
                        val docEmail = doc.getString("email")?.trim()?.lowercase() ?: ""
                        
                        val matchesComp = docCompId.equals(targetId, ignoreCase = true) ||
                            docCompId.equals(canonicalId, ignoreCase = true) ||
                            docCompId.equals(cleanId, ignoreCase = true)

                        if (matchesComp && docEmail != "abbasok@gmail.com" && !docEmail.contains("abbasok")) {
                            batch.delete(doc.reference)
                            hasBatch = true
                        }
                    }
                    if (hasBatch) {
                        batch.commit()
                    }
                }

                firestore.collection("dealers").get().addOnSuccessListener { snaps ->
                    val batch = firestore.batch()
                    var hasBatch = false
                    for (doc in snaps) {
                        val docComp = doc.getString("companyId") ?: doc.getString("company") ?: doc.getString("zone") ?: ""
                        val docCanonical = CompanyDataHelper.normalizeToCanonical(docComp) ?: docComp.replace("-", "").uppercase()
                        if (docCanonical == canonicalId || docComp.equals(targetId, ignoreCase = true)) {
                            batch.delete(doc.reference)
                            hasBatch = true
                        }
                    }
                    if (hasBatch) {
                        batch.commit()
                    }
                }

                val docId = CompanyDataHelper.getCanonicalDocId(targetId)
                firestore.collection("franchiseSettings").document(docId).delete()

                callback.onSuccess()
            }

            override fun onError(message: String) {
                callback.onError(message)
            }
        })
    }

    fun softResetCompany(companyId: String, callback: SimpleCallback) {
        val targetId = companyId.trim()
        if (targetId.isBlank()) {
            callback.onError("Company ID invalid hai")
            return
        }
        val canonicalId = CompanyDataHelper.normalizeToCanonical(targetId) ?: targetId.replace("-", "").uppercase()
        val cleanId = targetId.replace("-", "").uppercase()

        val rtdbRef = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com").getReference()
        val softResetNodes = listOf("employees", "attendance", "tracking", "employeePins")

        var completed = 0
        val total = softResetNodes.size

        for (node in softResetNodes) {
            rtdbRef.child(node).get().addOnSuccessListener { snapshot ->
                for (child in snapshot.children) {
                    if (isMatchingTargetCompany(child, targetId)) {
                        child.ref.removeValue()
                    }
                }
                completed++
                if (completed == total) {
                    val compRef = rtdbRef.child("companies")
                    compRef.child(targetId).child("employees").removeValue()
                    compRef.child(targetId).child("attendance").removeValue()
                    compRef.child(targetId).child("employeePins").removeValue()
                    compRef.child(targetId).child("tracking").removeValue()

                    compRef.child(canonicalId).child("employees").removeValue()
                    compRef.child(canonicalId).child("attendance").removeValue()
                    compRef.child(canonicalId).child("employeePins").removeValue()
                    compRef.child(canonicalId).child("tracking").removeValue()

                    compRef.child(cleanId).child("employees").removeValue()
                    compRef.child(cleanId).child("attendance").removeValue()
                    compRef.child(cleanId).child("employeePins").removeValue()
                    compRef.child(cleanId).child("tracking").removeValue()

                    callback.onSuccess()
                }
            }.addOnFailureListener {
                completed++
                if (completed == total) callback.onSuccess()
            }
        }
    }

    private fun purgeTargetRegistrationData(targetId: String, callback: SimpleCallback) {
        val rtdbRef = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com").getReference()
        val paths = listOf(
            rtdbRef.child("ApprovedDevices"),
            rtdbRef.child("PendingDevices"),
            rtdbRef.child("employeePins"),
            rtdbRef.child("employees"),
            rtdbRef.child("attendance"),
            rtdbRef.child("tracking"),
            rtdbRef.child("complaints"),
            rtdbRef.child("resolvedComplaints"),
            rtdbRef.child("geofenceLogs"),
            rtdbRef.child("adminNotifications"),
            rtdbRef.child("employeeNotifications"),
            rtdbRef.child("complaintEmployees"),
            rtdbRef.child("fuelLogs"),
            rtdbRef.child("reliefLogs")
        )

        var completed = 0
        val total = paths.size
        for (pathRef in paths) {
            pathRef.get().addOnSuccessListener { snapshot ->
                for (child in snapshot.children) {
                    if (isMatchingTargetCompany(child, targetId)) {
                        child.ref.removeValue()
                    }
                }
                completed++
                if (completed == total) callback.onSuccess()
            }.addOnFailureListener {
                completed++
                if (completed == total) callback.onSuccess()
            }
        }
    }

    private fun purgeAllRegistrationData(callback: SimpleCallback?) {
        val rtdbRef = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
        val masterNodes = listOf("ApprovedDevices", "PendingDevices", "employeePins", "employees", "attendance", "complaints", "tracking")
        for (node in masterNodes) {
            rtdbRef.getReference(node).removeValue()
        }
        callback?.onSuccess()
    }
}
