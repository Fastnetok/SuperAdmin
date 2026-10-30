package com.example.superadmin.models

data class Company(
    var companyId: String = "",
    var companyName: String = "",
    var city: String = "",
    var ownerName: String = "",
    var adminEmail: String = "",
    var adminPassword: String = "12345678",
    var licenseStatus: String = "ACTIVE",
    var licenseType: String = "Standard",
    var maxManagers: Int = 2,
    var maxSupervisors: Int = 3,
    var maxEmployees: Int = 20,
    var maxCustomers: Int = 100,
    var maxDealers: Int = 10,
    var licenseExpiry: Long = 0L,
    var createdAt: Long = 0L,
    var totalEmployees: Int = 0,
    var totalComplaints: Int = 0,
    var pendingComplaints: Int = 0,
    var resolvedComplaints: Int = 0
) {
    constructor() : this("", "", "", "", "", "12345678", "ACTIVE", "Standard", 2, 3, 20, 100, 10, 0L, 0L, 0, 0, 0, 0)
}