package com.example.superadmin

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.superadmin.WebViewRouter
import com.google.firebase.firestore.DocumentReference

class InspectorAuthManager {
    private var isInspectorAuthenticated = false
    private val REQUEST_INSPECT_PANEL = 9005

    fun resetSession() {
        isInspectorAuthenticated = false
    }

    fun inspect(activity: AppCompatActivity, franchiseDocRef: DocumentReference, currentZone: String) {
        if (isInspectorAuthenticated) {
            showInspectorPanelDialog(activity, franchiseDocRef, currentZone)
        } else {
            authenticateAndShow(activity, franchiseDocRef, currentZone)
        }
    }

    fun handleActivityResult(requestCode: Int, activity: AppCompatActivity, franchiseDocRef: DocumentReference, currentZone: String): Boolean {
        if (requestCode == REQUEST_INSPECT_PANEL) {
            // Re-open inspector dialog automatically when returning from inspecting any panel
            showInspectorPanelDialog(activity, franchiseDocRef, currentZone)
            return true
        }
        return false
    }

    private fun authenticateAndShow(activity: AppCompatActivity, franchiseDocRef: DocumentReference, currentZone: String) {
        val biometricManager = BiometricManager.from(activity)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        
        // If biometric is not available or not enrolled on device, allow entering directly for testing/fallback
        if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            isInspectorAuthenticated = true
            showInspectorPanelDialog(activity, franchiseDocRef, currentZone)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isInspectorAuthenticated = true
                Toast.makeText(activity, "Authentication Successful!", Toast.LENGTH_SHORT).show()
                showInspectorPanelDialog(activity, franchiseDocRef, currentZone)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                    Toast.makeText(activity, "Authentication failed: $errString", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(activity, "Biometric match nahi hua.", Toast.LENGTH_SHORT).show()
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ISP Panel Inspector Security")
            .setSubtitle("Panel inspect karne ke liye face ya fingerprint verify karein")
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun showInspectorPanelDialog(activity: AppCompatActivity, franchiseDocRef: DocumentReference, currentZone: String) {
        franchiseDocRef.get().addOnSuccessListener { snapshot ->
            val enabledMap = snapshot.get("enabledCompanies") as? Map<*, *>
            val companyOrder = listOf("EBONE", "WATEEN", "ZONG", "JAZZ")
            val availablePanels = mutableListOf<Pair<String, String>>()
            
            companyOrder.forEach { ispKey ->
                val isEnabled = (enabledMap?.get(ispKey) as? Boolean) ?: false
                if (isEnabled) {
                    val label = when (ispKey) {
                        "EBONE" -> "Ebone"
                        "WATEEN" -> "Wateen"
                        "ZONG" -> "Zong"
                        "JAZZ" -> "Jazz"
                        else -> ispKey
                    }
                    availablePanels.add(label to ispKey)
                }
            }

            if (availablePanels.isEmpty()) {
                Toast.makeText(activity, "No ISP panels enabled for this franchise in City & Franchise Management", Toast.LENGTH_LONG).show()
                return@addOnSuccessListener
            }

            val labels = availablePanels.map { prv -> prv.first }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("Inspect which panel?")
                .setItems(labels) { _, which ->
                    val selectedIsp = availablePanels[which].second
                    val targetActivity = WebViewRouter.getTargetActivity(activity, selectedIsp, currentZone)
                    val intent = Intent(activity, targetActivity).apply {
                        putExtra("selected_isp", selectedIsp)
                        putExtra("debug_tap_inspector", true)
                        putExtra("target_zone", currentZone)
                    }
                    activity.startActivityForResult(intent, REQUEST_INSPECT_PANEL)
                }
                .show()
        }
    }
}
