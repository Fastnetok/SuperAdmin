package com.example.superadmin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth

class UnlockActivity : AppCompatActivity() {

    private lateinit var fingerprintButton: ImageButton
    private lateinit var unlockPinInput: EditText
    private lateinit var unlockButton: Button
    private lateinit var statusText: TextView
    private lateinit var logoutButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock)

        fingerprintButton = findViewById(R.id.fingerprintButton)
        unlockPinInput = findViewById(R.id.unlockPinInput)
        unlockButton = findViewById(R.id.unlockButton)
        statusText = findViewById(R.id.unlockStatusText)
        logoutButton = findViewById(R.id.logoutButton)

        val biometricManager = BiometricManager.from(this)
        val canUseBiometric = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

        fingerprintButton.isEnabled = canUseBiometric
        fingerprintButton.alpha = if (canUseBiometric) 1f else 0.3f

        fingerprintButton.setOnClickListener {
            if (canUseBiometric) {
                showBiometricPrompt()
            } else {
                showPinFallback()
            }
        }

        unlockButton.setOnClickListener {
            val pin = unlockPinInput.text.toString().trim()
            if (SetPinActivity.checkPin(this, pin)) {
                proceedToDashboard()
            } else {
                statusText.text = "Galat PIN"
            }
        }

        logoutButton.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            SetPinActivity.clearPin(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // Try biometric automatically as soon as the screen opens without showing PIN
        if (canUseBiometric) {
            statusText.text = "Biometric verify ho raha hai..."
            showBiometricPrompt()
        } else {
            showPinFallback()
        }
    }

    private fun showPinFallback() {
        unlockPinInput.visibility = View.VISIBLE
        unlockButton.visibility = View.VISIBLE
        logoutButton.visibility = View.VISIBLE
        statusText.text = "Biometric available nahi hai. PIN darج karein."
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                proceedToDashboard()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                // If user cancels biometric or it errors, show PIN fallback
                showPinFallback()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                statusText.text = "Biometric match nahi hua. Dubara koshish karein."
            }
        }

        val biometricPrompt = BiometricPrompt(this, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("SuperAdmin Unlock")
            .setSubtitle("Fingerprint ya Face Unlock se login karein")
            .setNegativeButtonText("PIN use karein")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun proceedToDashboard() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
