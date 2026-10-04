package com.example.superadmin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.example.superadmin.superadmin.companymanager.CompanyManagerActivity
import com.google.firebase.auth.FirebaseAuth

class MenuActivity : BaseAdminActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        val currentUserEmail = FirebaseAuth.getInstance().currentUser?.email
        val menuManageCompanies = findViewById<TextView>(R.id.menuManageCompanies)

        if (currentUserEmail.equals("abbasok@gmail.com", ignoreCase = true) || currentUserEmail?.contains("abbasok", ignoreCase = true) == true) {
            menuManageCompanies.visibility = View.VISIBLE
            menuManageCompanies.setOnClickListener {
                startActivity(Intent(this, CompanyManagerActivity::class.java))
            }
        } else {
            menuManageCompanies.visibility = View.GONE
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

        findViewById<TextView>(R.id.menuLogout).setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            finish()
        }
    }
}