package com.example.superadmin

data class EmployeeItem(
    val employeeId: String = "",
    val name: String = "",
    val status: String = "",
    var isMonitored: Boolean = false
)
