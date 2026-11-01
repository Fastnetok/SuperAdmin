package com.example.superadmin.superadmin.companymanager

import android.util.Patterns

object CompanyValidator {

    data class ValidationResult(val isValid: Boolean, val errorMessage: String? = null)

    fun validate(
        companyName: String,
        city: String,
        ownerName: String,
        adminEmail: String = "",
        adminPassword: String = ""
    ): ValidationResult {
        if (companyName.isBlank()) {
            return ValidationResult(false, "Company ka naam khali nahi ho sakta")
        }
        if (city.isBlank()) {
            return ValidationResult(false, "Shehar (City) khali nahi ho sakta")
        }
        if (ownerName.isBlank()) {
            return ValidationResult(false, "Owner ka naam khali nahi ho sakta")
        }
        if (companyName.length < 3) {
            return ValidationResult(false, "Company ka naam kam se kam 3 harf ka ho")
        }
        if (adminEmail.isNotBlank() && !Patterns.EMAIL_ADDRESS.matcher(adminEmail).matches()) {
            return ValidationResult(false, "Sahi Email address darj karein")
        }
        if (adminPassword.isNotBlank() && adminPassword.length < 6) {
            return ValidationResult(false, "Password kam se kam 6 harf (characters) ka hona chahiye")
        }
        return ValidationResult(true)
    }
}