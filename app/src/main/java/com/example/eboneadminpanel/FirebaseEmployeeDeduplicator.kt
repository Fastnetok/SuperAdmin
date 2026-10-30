package com.example.superadmin

import android.util.Log

object FirebaseEmployeeDeduplicator {

    private const val TAG = "EmpDeduplicator"

    /**
     * Safe no-op deduplicator.
     * Prevents deletion of legitimate employee key aliases (androidId, phone, pin, uid).
     */
    fun cleanDuplicateEmployees(onComplete: (() -> Unit)? = null) {
        Log.d(TAG, "Employee deduplication bypass triggered - preserving all registration key aliases.")
        onComplete?.invoke()
    }
}
