package com.example.superadmin

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import com.example.superadmin.core.CompanyDataHelper
import com.example.superadmin.core.SessionManager
import com.example.superadmin.superadmin.companymanager.CompanyManagerActivity
import com.google.firebase.auth.FirebaseAuth

class MenuActivity : BaseAdminActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        // Automatically clean and purge all orphaned/ghost records from Realtime Database on startup
        com.example.superadmin.repositories.CompanyRepository().cleanOrphanedData()

        val currentUserEmail = FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase()
        Log.d("SuperAdminCheck", "Current User Email: '$currentUserEmail'")
        val menuManageCompanies = findViewById<TextView>(R.id.menuManageCompanies)
        val menuCityManagement = findViewById<TextView>(R.id.menuCityManagement)
        val menuAboutAppBuilder = findViewById<TextView>(R.id.menuAboutAppBuilder)

        menuAboutAppBuilder.setOnClickListener {
            showAboutAppBuilderDialog()
        }

        if (currentUserEmail == "abbasok@gmail.com" || currentUserEmail?.contains("abbasok") == true) {
            menuManageCompanies.visibility = View.VISIBLE
            menuManageCompanies.setOnClickListener {
                startActivity(Intent(this, CompanyManagerActivity::class.java))
            }
            menuCityManagement.visibility = View.VISIBLE
            menuCityManagement.setOnClickListener {
                startActivity(Intent(this, CityFranchiseManagementActivity::class.java))
            }
        } else {
            menuManageCompanies.visibility = View.GONE
            menuCityManagement.visibility = View.GONE
        }

        val currentZone = CompanyDataHelper.getCurrentCompanyId(this) ?: SessionManager(this).getActiveCompanyId() ?: "Okara"
        Log.d("CityPermissionDebug", "MenuActivity currentZone evaluated to: '$currentZone'")

        val menuViews = mapOf(
            "New Complaint" to findViewById<TextView>(R.id.menuAddComplaint),
            "Employee Biometric Attendance" to findViewById<TextView>(R.id.menuBiometricAttendance),
            "Office Timings" to findViewById<TextView>(R.id.menuOfficeTimings),
            "App Version Status" to findViewById<TextView>(R.id.menuAppVersion),
            "Admin Management" to findViewById<TextView>(R.id.menuAddEmployee),
            "All Employees" to findViewById<TextView>(R.id.menuAllEmployees),
            "Reports" to findViewById<TextView>(R.id.menuReports),
            "Movement Tracking" to findViewById<TextView>(R.id.menuMovementTracking),
            "Customer Billing" to findViewById<TextView>(R.id.menuCustomerBilling),
            "Add Customer" to findViewById<TextView>(R.id.menuAddCustomer),
            "ISP Panel Settings" to findViewById<TextView>(R.id.menuIspPanelSettings),
            "SMS Match Settings" to findViewById<TextView>(R.id.menuSmsMatchSettings),
            "Dealer Panel" to findViewById<TextView>(R.id.menuDealerPanel),
            "Auto Complaint Monitor" to findViewById<TextView>(R.id.menuAutoComplaintMonitor),
            "Settings" to findViewById<TextView>(R.id.menuSettings)
        )

        val isSuperAdmin = currentUserEmail == "abbasok@gmail.com" || currentUserEmail?.contains("abbasok") == true

        for ((featureName, view) in menuViews) {
            view?.let { v ->
                if (isSuperAdmin) {
                    v.visibility = View.VISIBLE
                } else {
                    CityPermissionManager.isFeatureEnabled(this, currentZone, featureName) { isEnabled ->
                        v.visibility = if (isEnabled) View.VISIBLE else View.GONE
                    }
                }
            }
        }

        findViewById<TextView>(R.id.menuAddComplaint).setOnClickListener {
            startActivity(Intent(this, AddComplaintActivity::class.java))
        }

        findViewById<TextView>(R.id.menuBiometricAttendance).setOnClickListener {
            startActivity(Intent(this, BiometricAttendanceActivity::class.java))
        }

        findViewById<TextView>(R.id.menuOfficeTimings).setOnClickListener {
            startActivity(Intent(this, OfficeTimmingsActivity::class.java))
        }

        findViewById<TextView>(R.id.menuAppVersion).setOnClickListener {
            startActivity(Intent(this, AppVersionActivity::class.java))
        }

        findViewById<TextView>(R.id.menuAddEmployee).setOnClickListener {
            startActivity(Intent(this, AddEmployeeActivity::class.java))
        }

        findViewById<TextView>(R.id.menuAllEmployees).setOnClickListener {
            startActivity(Intent(this, AllEmployeesActivity::class.java))
        }

        findViewById<TextView>(R.id.menuReports).setOnClickListener {
            startActivity(Intent(this, ReportsActivity::class.java))
        }

        findViewById<TextView>(R.id.menuMovementTracking).setOnClickListener {
            startActivity(Intent(this, MovementTrackingActivity::class.java))
        }

        findViewById<TextView>(R.id.menuCustomerBilling).setOnClickListener {
            startActivity(Intent(this, CustomerBillingActivity::class.java))
        }

        findViewById<TextView>(R.id.menuAddCustomer).setOnClickListener {
            startActivity(Intent(this, AddCustomerActivity::class.java))
        }

        findViewById<TextView>(R.id.menuIspPanelSettings).setOnClickListener {
            startActivity(Intent(this, IspPanelSettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.menuDealerPanel).setOnClickListener {
            startActivity(Intent(this, DealerDashboardActivity::class.java))
        }

        findViewById<TextView>(R.id.menuAutoComplaintMonitor).setOnClickListener {
            startActivity(Intent(this, AutoComplaintMonitorActivity::class.java))
        }

        findViewById<TextView>(R.id.menuSmsMatchSettings).setOnClickListener {
            startActivity(Intent(this, SmsMatchSettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.menuSettings)?.setOnClickListener {
            startActivity(Intent(this, SetPinActivity::class.java))
        }

        val menuCompanyLog = findViewById<TextView>(R.id.menuCompanyLog)
        if (currentUserEmail == "abbasok@gmail.com" || currentUserEmail?.contains("abbasok") == true) {
            menuCompanyLog?.visibility = View.VISIBLE
            menuCompanyLog?.setOnClickListener {
                startActivity(Intent(this, CompanyLogActivity::class.java))
            }
        } else {
            menuCompanyLog?.visibility = View.GONE
        }

        findViewById<TextView>(R.id.menuLogout).setOnClickListener {
            com.example.superadmin.core.AppActivityTracker.onUserLogout()
            FirebaseAuth.getInstance().signOut()
            SessionManager(this).clearSession()
            SetPinActivity.clearPin(this)
            val intent =Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun showAboutAppBuilderDialog() {
        val message = """
            📱 App Builder & Developer Information
            
            👤 Name: Muhammad Abbas (Lead App Builder & Developer)
            📞 Contact / WhatsApp: 03213951111
            
            🛠️ System & Developer Commands / Features:
            • Advanced SuperAdmin Architecture & Multi-Company Control
            • City & Franchise Permission Manager with Live Toggles
            • Automated SMS Payment Verification & Dealer Ledger Sync
            • Employee Biometric Attendance & Admin Override System
            • Network & ISP Panel Configuration with Secure MasterKey Encryption
            • Real-time Firebase Database Synchronization & Background Workers
            
            ✨ "Built with enterprise-grade reliability, cutting-edge Android development standards, and robust automation."
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("ℹ️ About App Builder")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
