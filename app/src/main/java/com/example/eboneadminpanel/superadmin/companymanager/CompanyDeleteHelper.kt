package com.example.superadmin.superadmin.companymanager

import android.app.AlertDialog
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.superadmin.models.Company

object CompanyDeleteHelper {

    fun promptSecureDelete(activity: AppCompatActivity, company: Company, viewModel: CompanyViewModel) {
        AlertDialog.Builder(activity)
            .setTitle("Delete Confirmation")
            .setMessage("Are you sure you want to delete this company?\n\nCompany Name: ${company.companyName}\nCompany ID: ${company.companyId}")
            .setPositiveButton("Yes") { dialog, _ ->
                dialog.dismiss()
                authenticateAndDelete(activity, company, viewModel)
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun authenticateAndDelete(activity: AppCompatActivity, company: Company, viewModel: CompanyViewModel) {
        val biometricManager = BiometricManager.from(activity)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        val canAuthenticate = biometricManager.canAuthenticate(authenticators)

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(
                activity,
                "Security Alert: Biometric authentication (Fingerprint/Face) setup nahi hai. Company delete nahi ki ja sakti.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Toast.makeText(activity, "Authentication Successful!", Toast.LENGTH_SHORT).show()
                viewModel.deleteCompany(company.companyId)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(
                    activity,
                    "Authentication failed or cancelled: $errString. Company delete nahi hui.",
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
            .setTitle("Company Delete Verification")
            .setSubtitle("Company '${company.companyName}' (${company.companyId}) delete karne ke liye identity verify karein")
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
