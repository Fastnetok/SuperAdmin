package com.example.superadmin

import java.util.Locale

class EmployeeReportManager {

    private fun norm(name: String): String = name.trim().lowercase(Locale.getDefault())

    fun getRepeatComplaints(
        complaints: List<Complaint>,
        employeeName: String
    ): Int {
        val userIds = mutableListOf<String>()
        var repeatCount = 0
        val targetNorm = norm(employeeName)

        for (complaint in complaints) {
            if (norm(complaint.assignedTo) == targetNorm) {
                if (userIds.contains(complaint.userId)) {
                    repeatCount++
                } else {
                    userIds.add(complaint.userId)
                }
            }
        }
        return repeatCount
    }

    fun getTotalComplaints(
        complaints: List<Complaint>,
        employeeName: String
    ): Int {
        val targetNorm = norm(employeeName)
        return complaints.count { norm(it.assignedTo) == targetNorm }
    }

    fun getResolvedComplaints(
        complaints: List<Complaint>,
        employeeName: String
    ): Int {
        val targetNorm = norm(employeeName)
        return complaints.count {
            norm(it.assignedTo) == targetNorm && it.status.equals("Resolved", ignoreCase = true)
        }
    }

    fun getPendingComplaints(
        complaints: List<Complaint>,
        employeeName: String
    ): Int {
        val targetNorm = norm(employeeName)
        return complaints.count {
            norm(it.assignedTo) == targetNorm && !it.status.equals("Resolved", ignoreCase = true)
        }
    }
}