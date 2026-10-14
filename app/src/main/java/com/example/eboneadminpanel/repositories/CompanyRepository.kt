package com.example.superadmin.repositories

import com.example.superadmin.core.FirestorePaths
import com.example.superadmin.models.Company
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore

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

    fun checkEmailExists(email: String, excludeCompanyId: String? = null, callback: EmailCheckCallback) {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isEmpty()) {
            callback.onResult(false)
            return
        }
        companiesRef.get()
            .addOnSuccessListener { snapshot ->
                val exists = snapshot.documents.any { doc ->
                    val docId = doc.id
                    if (excludeCompanyId != null && docId == excludeCompanyId) {
                        false
                    } else {
                        val companyEmail = doc.getString("adminEmail")?.trim()?.lowercase()
                        companyEmail == cleanEmail
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
                callback.onSuccess()
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
                    // Auto-purge all orphan registration data when 0 companies exist
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

    fun resetCompanyTestData(companyId: String, callback: SimpleCallback) {
        val targetId = companyId.trim()
        if (targetId.isBlank()) {
            callback.onError("Company ID invalid hai")
            return
        }
        val cleanId = targetId.replace("-", "")

        purgeTargetRegistrationData(targetId, cleanId, object : SimpleCallback {
            override fun onSuccess() {
                val rtdbRef = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
                val companySubNodes = listOf(
                    "employees", "ApprovedDevices", "PendingDevices", "employeePins",
                    "attendance", "complaints", "resolvedComplaints", "geofenceLogs",
                    "adminNotifications", "employeeNotifications", "complaintEmployees"
                )

                val targetCompRef = rtdbRef.getReference("companies").child(targetId)
                val updateMap = mutableMapOf<String, Any?>()
                for (subNode in companySubNodes) {
                    updateMap[subNode] = null
                }

                // Delete any company specific admins from Firestore when resetting
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("admins").whereEqualTo("companyId", targetId).get().addOnSuccessListener { snaps ->
                    val batch = firestore.batch()
                    for (doc in snaps) {
                        val email = doc.getString("email")?.trim()?.lowercase()
                        if (email != "abbasok@gmail.com" && email?.contains("abbasok") != true) {
                            batch.delete(doc.reference)
                        }
                    }
                    batch.commit()
                }

                targetCompRef.updateChildren(updateMap).addOnCompleteListener {
                    callback.onSuccess()
                }
            }

            override fun onError(message: String) {
                callback.onError(message)
            }
        })
    }

    fun deleteCompany(companyId: String, callback: SimpleCallback) {
        val targetId = companyId.trim()
        if (targetId.isBlank()) {
            callback.onError("Company ID invalid hai")
            return
        }
        val cleanId = targetId.replace("-", "")
        val cityPrefix = if (targetId.contains("-")) targetId.substringBefore("-").uppercase() else targetId.take(3).uppercase()

        companiesRef.get().addOnSuccessListener { snapshot ->
            val docsToDelete = snapshot.documents.filter { doc ->
                val docCompanyId = doc.getString("companyId")?.trim()
                doc.id.equals(targetId, ignoreCase = true) ||
                doc.id.equals(cleanId, ignoreCase = true) ||
                (docCompanyId != null && (docCompanyId.equals(targetId, ignoreCase = true) || docCompanyId.equals(cleanId, ignoreCase = true)))
            }

            val batch = db.batch()
            for (doc in docsToDelete) {
                batch.delete(doc.reference)
            }

            batch.commit().addOnSuccessListener {
                companiesRef.get().addOnSuccessListener { remainingSnap ->
                    val totalRemainingCompanies = remainingSnap.documents.size

                    if (totalRemainingCompanies == 0) {
                        purgeAllRegistrationData(object : SimpleCallback {
                            override fun onSuccess() {
                                db.collection(FirestorePaths.COUNTERS).document(cityPrefix).delete()
                                callback.onSuccess()
                            }

                            override fun onError(message: String) {
                                callback.onSuccess()
                            }
                        })
                    } else {
                        // Purge records matching this targetId
                        purgeTargetRegistrationData(targetId, cleanId, object : SimpleCallback {
                            override fun onSuccess() {
                                val remainingForCity = remainingSnap.documents.any { doc ->
                                    val cId = (doc.getString("companyId")?.trim() ?: doc.id.trim()).uppercase()
                                    cId.startsWith("$cityPrefix-") || cId.startsWith(cityPrefix)
                                }
                                if (!remainingForCity) {
                                    db.collection(FirestorePaths.COUNTERS).document(cityPrefix).delete()
                                }
                                callback.onSuccess()
                            }

                            override fun onError(message: String) {
                                callback.onSuccess()
                            }
                        })
                    }
                }.addOnFailureListener {
                    callback.onSuccess()
                }
            }.addOnFailureListener { e ->
                callback.onError(e.message ?: "Company delete nahi ho saki")
            }
        }.addOnFailureListener { e ->
            callback.onError(e.message ?: "Companies list fetch nahi ho saki")
        }
    }

    /**
     * Purges ALL registration nodes across Firestore and RTDB when 0 companies exist.
     */
    fun purgeAllRegistrationData(callback: SimpleCallback?) {
        val fsCollections = listOf(
            "employees", "ApprovedDevices", "PendingDevices", "employeePins",
            "registeredDevices", "approved_devices", "pending_devices", "users",
            "field_managers", "device_approvals", "deviceApprovals", "admins",
            "complaints", "resolvedComplaints", "attendance", "geofenceLogs",
            "adminNotifications", "employeeNotifications", "complaintEmployees",
            "employeeComplaints"
        )

        for (colName in fsCollections) {
            db.collection(colName).get().addOnSuccessListener { colSnap ->
                val batch = db.batch()
                for (doc in colSnap.documents) {
                    // Do not delete SuperAdmin from admins collection
                    if (colName == "admins") {
                        val email = doc.getString("email")?.trim()?.lowercase()
                        if (email == "abbasok@gmail.com" || email?.contains("abbasok") == true) {
                            continue
                        }
                    }
                    batch.delete(doc.reference)
                }
                batch.commit()
            }
        }

        val rtdbRef = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
        val rtdbNodes = listOf(
            "companies", "employees", "ApprovedDevices", "PendingDevices", "employeePins",
            "registeredDevices", "approved_devices", "pending_devices", "users",
            "field_managers", "device_approvals", "deviceApprovals", "activeSessions",
            "complaints", "resolvedComplaints", "attendance", "geofenceLogs",
            "adminNotifications", "employeeNotifications", "complaintEmployees", "employeeComplaints"
        )

        var remaining = rtdbNodes.size
        fun checkFinished() {
            remaining--
            if (remaining <= 0) {
                callback?.onSuccess()
            }
        }

        for (nodeName in rtdbNodes) {
            rtdbRef.getReference(nodeName).removeValue().addOnCompleteListener {
                checkFinished()
            }
        }
    }

    /**
     * Purges target company registration nodes across Firestore and RTDB.
     */
    fun purgeTargetRegistrationData(targetId: String, cleanId: String, callback: SimpleCallback) {
        val fsCollections = listOf(
            "employees", "ApprovedDevices", "PendingDevices", "employeePins",
            "registeredDevices", "approved_devices", "pending_devices", "users",
            "field_managers", "device_approvals", "deviceApprovals", "admins",
            "complaints", "resolvedComplaints", "attendance", "geofenceLogs",
            "adminNotifications", "employeeNotifications", "complaintEmployees",
            "employeeComplaints"
        )

        fun matchesCompanyFields(cId: String?, cName: String?, cCode: String?, cCompany: String?): Boolean {
            val allFields = listOf(cId, cName, cCode, cCompany)
            return allFields.any { field ->
                field != null && field.isNotBlank() && (
                    field.trim().equals(targetId, ignoreCase = true) ||
                    field.trim().equals(cleanId, ignoreCase = true) ||
                    field.trim().replace("-", "").equals(cleanId, ignoreCase = true)
                )
            }
        }

        for (colName in fsCollections) {
            db.collection(colName).get().addOnSuccessListener { colSnap ->
                val batch = db.batch()
                for (doc in colSnap.documents) {
                    val cId = doc.getString("companyId")
                    val cName = doc.getString("companyName")
                    val cCode = doc.getString("companyCode")
                    val cCompany = doc.getString("company")
                    if (matchesCompanyFields(cId, cName, cCode, cCompany)) {
                        batch.delete(doc.reference)
                    }
                }
                batch.commit()
            }
        }

        val rtdbRef = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
        val rtdbNodes = listOf(
            "companies", "employees", "ApprovedDevices", "PendingDevices", "employeePins",
            "registeredDevices", "approved_devices", "pending_devices", "users",
            "field_managers", "device_approvals", "deviceApprovals", "activeSessions",
            "complaints", "resolvedComplaints", "attendance", "geofenceLogs",
            "adminNotifications", "employeeNotifications", "complaintEmployees", "employeeComplaints"
        )

        var remaining = rtdbNodes.size
        fun checkFinished() {
            remaining--
            if (remaining <= 0) {
                callback.onSuccess()
            }
        }

        for (nodeName in rtdbNodes) {
            val nodeRef = rtdbRef.getReference(nodeName)
            nodeRef.get().addOnSuccessListener { nodeSnap ->
                val nodeUpdates = mutableMapOf<String, Any?>()
                for (c in nodeSnap.children) {
                    val cId = c.child("companyId").getValue(String::class.java)
                    val cName = c.child("companyName").getValue(String::class.java)
                    val cCode = c.child("companyCode").getValue(String::class.java)
                    val cCompany = c.child("company").getValue(String::class.java)

                    if (matchesCompanyFields(cId, cName, cCode, cCompany) || c.key.equals(targetId, ignoreCase = true) || c.key.equals(cleanId, ignoreCase = true)) {
                        nodeUpdates[c.key!!] = null
                    } else {
                        // Check nested children (e.g. employeeComplaints -> employeeKey -> complaintKey)
                        for (subChild in c.children) {
                            val subCId = subChild.child("companyId").getValue(String::class.java)
                            val subCName = subChild.child("companyName").getValue(String::class.java)
                            val subCCode = subChild.child("companyCode").getValue(String::class.java)
                            val subCComp = subChild.child("company").getValue(String::class.java)
                            if (matchesCompanyFields(subCId, subCName, subCCode, subCComp) || subChild.key.equals(targetId, ignoreCase = true) || subChild.key.equals(cleanId, ignoreCase = true)) {
                                nodeUpdates["${c.key}/${subChild.key}"] = null
                            }
                        }
                    }
                }
                if (nodeUpdates.isNotEmpty()) {
                    nodeRef.updateChildren(nodeUpdates).addOnCompleteListener { checkFinished() }
                } else {
                    checkFinished()
                }
            }.addOnFailureListener { checkFinished() }
        }
    }

    /**
     * Scans and removes any orphaned data (complaints, attendance, etc.)
     * belonging to deleted or non-existent companies.
     */
    fun cleanOrphanedData(callback: SimpleCallback? = null) {
        companiesRef.get().addOnSuccessListener { snapshot ->
            val activeCompanyIds = snapshot.documents.mapNotNull { doc ->
                doc.getString("companyId")?.trim()?.uppercase() ?: doc.id.trim().uppercase()
            }.toSet()

            if (activeCompanyIds.isEmpty()) {
                purgeAllRegistrationData(callback)
                return@addOnSuccessListener
            }

            val cleanActiveCompanyIds = activeCompanyIds.map { it.replace("-", "") }.toSet()

            fun matchesAnyActive(cId: String?, cName: String?, cCode: String?, cCompany: String?): Boolean {
                val allFields = listOf(cId, cName, cCode, cCompany).filterNotNull().filter { it.isNotBlank() }
                if (allFields.isEmpty()) return true
                return allFields.any { field ->
                    val clean = field.trim().uppercase().replace("-", "")
                    val raw = field.trim().uppercase()
                    activeCompanyIds.contains(raw) || cleanActiveCompanyIds.contains(clean)
                }
            }

            val rtdbRef = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
            val rtdbNodes = listOf(
                "complaints", "resolvedComplaints", "employeeComplaints", "attendance",
                "geofenceLogs", "adminNotifications", "employeeNotifications", "complaintEmployees"
            )

            var remaining = rtdbNodes.size
            fun checkFinished() {
                remaining--
                if (remaining <= 0) {
                    callback?.onSuccess()
                }
            }

            for (nodeName in rtdbNodes) {
                val nodeRef = rtdbRef.getReference(nodeName)
                nodeRef.get().addOnSuccessListener { nodeSnap ->
                    val nodeUpdates = mutableMapOf<String, Any?>()
                    for (c in nodeSnap.children) {
                        val cId = c.child("companyId").getValue(String::class.java)
                        val cName = c.child("companyName").getValue(String::class.java)
                        val cCode = c.child("companyCode").getValue(String::class.java)
                        val cCompany = c.child("company").getValue(String::class.java)

                        val hasFields = listOf(cId, cName, cCode, cCompany).any { !it.isNullOrBlank() }
                        if (hasFields && !matchesAnyActive(cId, cName, cCode, cCompany)) {
                            nodeUpdates[c.key!!] = null
                        } else {
                            // Check nested children (e.g. employeeComplaints -> employeeKey -> complaintKey)
                            var allSubChildrenOrphaned = c.children.count() > 0
                            for (subChild in c.children) {
                                val subCId = subChild.child("companyId").getValue(String::class.java)
                                val subCName = subChild.child("companyName").getValue(String::class.java)
                                val subCCode = subChild.child("companyCode").getValue(String::class.java)
                                val subCComp = subChild.child("company").getValue(String::class.java)

                                val subHasFields = listOf(subCId, subCName, subCCode, subCComp).any { !it.isNullOrBlank() }
                                if (subHasFields && !matchesAnyActive(subCId, subCName, subCCode, subCComp)) {
                                    nodeUpdates["${c.key}/${subChild.key}"] = null
                                } else if (!subHasFields) {
                                    allSubChildrenOrphaned = false
                                } else {
                                    allSubChildrenOrphaned = false
                                }
                            }
                            if (allSubChildrenOrphaned) {
                                nodeUpdates[c.key!!] = null
                            }
                        }
                    }
                    if (nodeUpdates.isNotEmpty()) {
                        nodeRef.updateChildren(nodeUpdates).addOnCompleteListener { checkFinished() }
                    } else {
                        checkFinished()
                    }
                }.addOnFailureListener { checkFinished() }
            }
        }.addOnFailureListener {
            callback?.onError("Failed to fetch active companies for cleanup")
        }
    }
}
