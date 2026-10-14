package com.example.superadmin

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.example.superadmin.core.RoleManager
import com.example.superadmin.core.SessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
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
            val email = emailInput.text.toString().trim().lowercase()
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
                .addOnFailureListener { authError ->
                    // Fallback: Check if this email belongs to a registered company in Firestore
                    checkFirestoreCompanyLogin(email, password, authError)
                }
        }

        // Forgot Password link
        val forgotPasswordText = findViewById<TextView>(R.id.forgotPasswordText)
        forgotPasswordText.setOnClickListener {
            showForgotPasswordDialog()
        }
    }

    private fun checkFirestoreCompanyLogin(email: String, password: String, authError: Exception) {
        db.collection("companies")
            .whereEqualTo("adminEmail", email)
            .get()
            .addOnSuccessListener { querySnapshot ->
                val companyDoc = querySnapshot.documents.firstOrNull()
                if (companyDoc == null) {
                    // Try case-insensitive fallback search
                    db.collection("companies").get().addOnSuccessListener { allDocs ->
                        val matched = allDocs.documents.firstOrNull { doc ->
                            doc.getString("adminEmail")?.trim()?.equals(email, ignoreCase = true) == true
                        }
                        if (matched != null) {
                            processCompanyLogin(matched, email, password)
                        } else {
                            loginButton.isEnabled = true
                            statusText.text = "Login fail: ${authError.message}"
                        }
                    }.addOnFailureListener {
                        loginButton.isEnabled = true
                        statusText.text = "Login fail: ${authError.message}"
                    }
                    return@addOnSuccessListener
                }
                processCompanyLogin(companyDoc, email, password)
            }
            .addOnFailureListener {
                loginButton.isEnabled = true
                statusText.text = "Login fail: ${authError.message}"
            }
    }

    private fun processCompanyLogin(
        companyDoc: DocumentSnapshot,
        email: String,
        password: String
    ) {
        val savedPassword = companyDoc.getString("adminPassword") ?: ""
        val licenseStatus = companyDoc.getString("licenseStatus") ?: "ACTIVE"
        val licenseExpiry = companyDoc.getLong("licenseExpiry") ?: 0L
        val companyName = companyDoc.getString("companyName") ?: ""
        val companyId = companyDoc.getString("companyId")?.trim()?.uppercase()
            ?.ifEmpty { companyDoc.id.trim().uppercase() } ?: companyDoc.id.trim().uppercase()

        if (savedPassword.trim() != password.trim()) {
            loginButton.isEnabled = true
            statusText.text = "Login fail: Incorrect password for company admin"
            return
        }

        if (licenseStatus.uppercase() != "ACTIVE" && licenseStatus.uppercase() != "DEMO") {
            loginButton.isEnabled = true
            statusText.text = "Login fail: Company license active nahi hai ($licenseStatus)"
            return
        }

        if (licenseExpiry > 0 && licenseExpiry < System.currentTimeMillis()) {
            loginButton.isEnabled = true
            statusText.text = "Login fail: Company license expire ho chuki hai"
            return
        }

        // Credentials & License are valid! Automatically register/authenticate in Firebase Auth
        statusText.text = "Signing in..."
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { createResult ->
                val uid = createResult.user?.uid
                if (uid != null) {
                    db.collection("admins").document(uid).set(
                        mapOf(
                            "role" to "company_admin",
                            "companyId" to companyId,
                            "companyName" to companyName,
                            "email" to email,
                            "active" to true
                        )
                    ).addOnCompleteListener {
                        ensureAdminAndProceed(uid)
                    }
                } else {
                    ensureAdminAndProceed(null)
                }
            }
            .addOnFailureListener {
                // If createUser failed because account already exists, proceed with signIn
                auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener { signInResult ->
                        val uid = signInResult.user?.uid
                        if (uid != null) {
                            db.collection("admins").document(uid).set(
                                mapOf(
                                    "role" to "company_admin",
                                    "companyId" to companyId,
                                    "companyName" to companyName,
                                    "email" to email,
                                    "active" to true
                                )
                            ).addOnCompleteListener {
                                ensureAdminAndProceed(uid)
                            }
                        } else {
                            ensureAdminAndProceed(null)
                        }
                    }
                    .addOnFailureListener { signInError ->
                        loginButton.isEnabled = true
                        statusText.text = "Auth Error: ${signInError.message}"
                    }
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

        RoleManager.checkCurrentUserRole(object : RoleManager.RoleCheckCallback {
            override fun onRoleFound(role: String, companyId: String?) {
                SessionManager(this@LoginActivity).saveSession(uid, role, companyId)
                proceedToNextScreen()
            }

            override fun onError(message: String) {
                auth.signOut()
                SessionManager(this@LoginActivity).clearSession()
                statusText.text = message
                loginButton.isEnabled = true
                Toast.makeText(this@LoginActivity, message, Toast.LENGTH_LONG).show()
            }
        })
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
        val currentUser = auth.currentUser
        val uid = currentUser?.uid

        if (uid != null) {
            RoleManager.checkCurrentUserRole(object : RoleManager.RoleCheckCallback {
                override fun onRoleFound(role: String, companyId: String?) {
                    SessionManager(this@LoginActivity).saveSession(uid, role, companyId)
                    if (SetPinActivity.isPinSet(this@LoginActivity)) {
                        startActivity(Intent(this@LoginActivity, UnlockActivity::class.java))
                        finish()
                    } else {
                        proceedToNextScreen()
                    }
                }

                override fun onError(message: String) {
                    auth.signOut()
                    SessionManager(this@LoginActivity).clearSession()
                    statusText.text = message
                    loginButton.isEnabled = true
                }
            })
        }
    }
}
