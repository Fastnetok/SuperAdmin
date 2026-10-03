package com.example.superadmin

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var statusText: TextView

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        passwordInput.setText("12345678")
        loginButton = findViewById(R.id.loginButton)
        statusText = findViewById(R.id.statusText)

        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                statusText.text = "Email aur password dono likhein"
                return@setOnClickListener
            }

            loginButton.isEnabled = false
            statusText.text = "Signing in..."

            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                    ensureAdminAndProceed(result.user?.uid)
                }
                .addOnFailureListener { error ->
                    // Auto create account for testing if it doesn't exist
                    auth.createUserWithEmailAndPassword(email, password)
                        .addOnSuccessListener { createResult ->
                            ensureAdminAndProceed(createResult.user?.uid)
                        }
                        .addOnFailureListener { createError ->
                            loginButton.isEnabled = true
                            statusText.text = "Login fail: ${error.message}"
                        }
                }
        }

        // NEW: Forgot Password link
        val forgotPasswordText = findViewById<TextView>(R.id.forgotPasswordText)
        forgotPasswordText.setOnClickListener {
            showForgotPasswordDialog()
        }
    }

    private fun showForgotPasswordDialog() {
        val input = EditText(this)
        input.hint = "Apna email likhein"

        AlertDialog.Builder(this)
            .setTitle("Password Reset")
            .setMessage("Email daalein, hum aapko reset link bhej denge")
            .setView(input)
            .setPositiveButton("Bhejein") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isEmpty()) {
                    Toast.makeText(this, "Email likhein", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                auth.sendPasswordResetEmail(email)
                    .addOnSuccessListener {
                        Toast.makeText(
                            this,
                            "Reset link $email par bhej diya gaya",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(
                            this,
                            "Nahi bhej saka: ${it.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ensureAdminAndProceed(uid: String?) {
        if (uid == null) {
            statusText.text = "Login failed, try again"
            loginButton.isEnabled = true
            return
        }

        db.collection("admins")
            .document(uid)
            .get()
            .addOnSuccessListener { document ->
                if (!document.exists()) {
                    db.collection("admins")
                        .document(uid)
                        .set(mapOf("role" to "owner", "active" to true))
                        .addOnSuccessListener {
                            proceedToNextScreen()
                        }
                        .addOnFailureListener {
                            proceedToNextScreen()
                        }
                } else {
                    proceedToNextScreen()
                }
            }
            .addOnFailureListener {
                proceedToNextScreen()
            }
    }

    private fun proceedToNextScreen() {
        if (SetPinActivity.isPinSet(this)) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            startActivity(Intent(this, SetPinActivity::class.java))
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        // Already signed in from a previous session?
        val uid = auth.currentUser?.uid
        if (uid != null && SetPinActivity.isPinSet(this)) {
            startActivity(Intent(this, UnlockActivity::class.java))
            finish()
        } else if (uid != null) {
            ensureAdminAndProceed(uid)
        }
    }
}