package com.example.superadmin.core

import android.util.Log

object StaleDataPurger {

    private const val TAG = "StaleDataPurger"

    /**
     * Safe no-op purger.
     * Preserves all registered employee data across active companies.
     */
    fun purgeStaleEmployees(onComplete: (() -> Unit)? = null) {
        Log.d(TAG, "Stale data purge bypass triggered - preserving all employee registration records.")
        onComplete?.invoke()
    }
}
