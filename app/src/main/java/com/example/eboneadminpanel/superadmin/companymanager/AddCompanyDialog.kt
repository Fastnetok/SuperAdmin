package com.example.superadmin.superadmin.companymanager

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.example.superadmin.R

object AddCompanyDialog {

    fun show(context: Context, viewModel: CompanyViewModel) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_add_company, null)

        val etCompanyName = view.findViewById<EditText>(R.id.etCompanyName)
        val etCity = view.findViewById<EditText>(R.id.etCity)
        val etOwnerName = view.findViewById<EditText>(R.id.etOwnerName)
        val etAdminEmail = view.findViewById<EditText>(R.id.etAdminEmail)
        val etAdminPassword = view.findViewById<EditText>(R.id.etAdminPassword)
        val etMaxManagers = view.findViewById<EditText>(R.id.etMaxManagers)
        val etMaxSupervisors = view.findViewById<EditText>(R.id.etMaxSupervisors)
        val etMaxEmployees = view.findViewById<EditText>(R.id.etMaxEmployees)
        val etMaxCustomers = view.findViewById<EditText>(R.id.etMaxCustomers)
        val etMaxDealers = view.findViewById<EditText>(R.id.etMaxDealers)
        val etLicenseStatus = view.findViewById<EditText>(R.id.etLicenseStatus)
        val etExpiryDays = view.findViewById<EditText>(R.id.etExpiryDays)

        AlertDialog.Builder(context)
            .setTitle("Nayi Company Add Karein")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val companyName = etCompanyName.text.toString().trim()
                val city = etCity.text.toString().trim()
                val ownerName = etOwnerName.text.toString().trim()
                val adminEmail = etAdminEmail.text.toString().trim()
                val adminPassword = etAdminPassword.text.toString().trim().ifEmpty { "12345678" }
                val maxMgr = etMaxManagers.text.toString().toIntOrNull() ?: 2
                val maxSup = etMaxSupervisors.text.toString().toIntOrNull() ?: 3
                val maxEmp = etMaxEmployees.text.toString().toIntOrNull() ?: 20
                val maxCust = etMaxCustomers.text.toString().toIntOrNull() ?: 100
                val maxDealers = etMaxDealers.text.toString().toIntOrNull() ?: 10
                val status = etLicenseStatus.text.toString().trim().uppercase().ifEmpty { "ACTIVE" }
                val days = etExpiryDays.text.toString().toIntOrNull() ?: 30
                val expiryMillis = System.currentTimeMillis() + (days * 24L * 60L * 60L * 1000L)

                val result = CompanyValidator.validate(companyName, city, ownerName)
                if (result.isValid) {
                    viewModel.addNewCompany(companyName, city, ownerName, adminEmail, adminPassword, maxMgr, maxSup, maxEmp, maxCust, maxDealers, status, expiryMillis)
                } else {
                    Toast.makeText(context, result.errorMessage, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}