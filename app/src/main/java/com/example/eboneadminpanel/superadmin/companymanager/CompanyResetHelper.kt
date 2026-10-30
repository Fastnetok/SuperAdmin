package com.example.superadmin.superadmin.companymanager

import android.app.AlertDialog
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.superadmin.models.Company

object CompanyResetHelper {

    fun promptSoftReset(activity: AppCompatActivity, company: Company, viewModel: CompanyViewModel) {
        AlertDialog.Builder(activity)
            .setTitle("🔄 Soft Reset — ${company.companyName} (${company.companyId})")
            .setMessage("Soft Reset sirf is company (${company.companyId}) ke active employees aur attendance logs ko clear kare ga, baqi profile aur configuration safe rahe gi.\n\nAage barhne ke liye Biometric (Fingerprint/Face) verification darkar hai.")
            .setPositiveButton("Authenticate & Soft Reset") { dialog, _ ->
                dialog.dismiss()
                authenticateAndExecute(activity, "Soft Reset — ${company.companyName}") {
                    viewModel.softResetCompany(company)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun promptHardReset(activity: AppCompatActivity, company: Company, viewModel: CompanyViewModel) {
        AlertDialog.Builder(activity)
            .setTitle("⚠️ Hard Reset (00) — ${company.companyName} (${company.companyId})")
            .setMessage("Hard Reset is company (${company.companyId}) ka tamam data (employees, approved devices, attendance, tracking, complaints) mukammal tor par zero (00) kar de ga.\n\nAage barhne ke liye Biometric (Fingerprint/Face) verification darkar hai.")
            .setPositiveButton("Authenticate & Hard Reset") { dialog, _ ->
                dialog.dismiss()
                authenticateAndExecute(activity, "Hard Reset (00) — ${company.companyName}") {
                    viewModel.hardResetCompany(company)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun authenticateAndExecute(activity: AppCompatActivity, actionTitle: String, onAuthenticated: () -> Unit) {
        val biometricManager = BiometricManager.from(activity)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        val canAuthenticate = biometricManager.canAuthenticate(authenticators)

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(
                activity,
                "Security Alert: Biometric authentication (Fingerprint/Face) setup nahi hai. Action cancel kiya gaya.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Toast.makeText(activity, "Authentication Successful!", Toast.LENGTH_SHORT).show()
                onAuthenticated()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(
                    activity,
                    "Authentication failed or cancelled: $errString",
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(activity, "Biometric match nahi hua. Dubara try karein.", Toast.LENGTH_SHORT).show()
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Security Verification")
            .setSubtitle("Confirm '$actionTitle'")
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
