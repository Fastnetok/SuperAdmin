package com.example.superadmin

import android.util.Log

object ComplaintSeenHelper {

    private const val TAG = "ComplaintSeenHelper"

    /**
     * NOTE: SuperAdmin Panel is strictly READ-ONLY for employee seen status.
     * Only the Employee App on the employee's physical device writes `seenByEmployee = true`
     * and `seenTime` to Firebase when the employee actually opens the complaint detail screen.
     */
    fun markComplaintsAsSeenForEmployee(employeeName: String) {
        // No-op on Admin Panel to ensure Admin panel never marks complaints seen on behalf of employees.
        Log.d(TAG, "Admin Panel read-only check for $employeeName - seen status is handled by Employee App.")
    }
}
