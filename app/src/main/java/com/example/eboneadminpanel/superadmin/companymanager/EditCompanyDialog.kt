package com.example.superadmin.superadmin.companymanager

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.example.superadmin.R
import com.example.superadmin.models.Company

object EditCompanyDialog {

    fun show(context: Context, company: Company, viewModel: CompanyViewModel) {
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

        etCompanyName.setText(company.companyName)
        etCity.setText(company.city)
        etOwnerName.setText(company.ownerName)
        etAdminEmail.setText(company.adminEmail)
        etAdminPassword.setText(company.adminPassword)
        etMaxManagers.setText(company.maxManagers.toString())
        etMaxSupervisors.setText(company.maxSupervisors.toString())
        etMaxEmployees.setText(company.maxEmployees.toString())
        etMaxCustomers.setText(company.maxCustomers.toString())
        etMaxDealers.setText(company.maxDealers.toString())
        etLicenseStatus.setText(company.licenseStatus)

        val currentDays = if (company.licenseExpiry > System.currentTimeMillis()) {
            ((company.licenseExpiry - System.currentTimeMillis()) / (24L * 60L * 60L * 1000L)).toInt()
        } else {
            30
        }
        etExpiryDays.setText(currentDays.toString())

        AlertDialog.Builder(context)
            .setTitle("Company Edit Karein (${company.companyId})")
            .setView(view)
            .setPositiveButton("Update") { _, _ ->
                val companyName = etCompanyName.text.toString().trim()
                val city = etCity.text.toString().trim()
                val ownerName = etOwnerName.text.toString().trim()
                val adminEmail = etAdminEmail.text.toString().trim()
                val adminPassword = etAdminPassword.text.toString().trim().ifEmpty { company.adminPassword }
                val maxMgr = etMaxManagers.text.toString().toIntOrNull() ?: company.maxManagers
                val maxSup = etMaxSupervisors.text.toString().toIntOrNull() ?: company.maxSupervisors
                val maxEmp = etMaxEmployees.text.toString().toIntOrNull() ?: company.maxEmployees
                val maxCust = etMaxCustomers.text.toString().toIntOrNull() ?: company.maxCustomers
                val maxDealers = etMaxDealers.text.toString().toIntOrNull() ?: company.maxDealers
                val status = etLicenseStatus.text.toString().trim().uppercase().ifEmpty { company.licenseStatus }
                val days = etExpiryDays.text.toString().toIntOrNull() ?: currentDays
                val expiryMillis = System.currentTimeMillis() + (days * 24L * 60L * 60L * 1000L)

                val result = CompanyValidator.validate(companyName, city, ownerName)
                if (result.isValid) {
                    val updatedCompany = company.copy(
                        companyName = companyName,
                        city = city,
                        ownerName = ownerName,
                        adminEmail = adminEmail,
                        adminPassword = adminPassword,
                        maxManagers = maxMgr,
                        maxSupervisors = maxSup,
                        maxEmployees = maxEmp,
                        maxCustomers = maxCust,
                        maxDealers = maxDealers,
                        licenseStatus = status,
                        licenseExpiry = expiryMillis
                    )
                    viewModel.updateCompany(updatedCompany)
                } else {
                    Toast.makeText(context, result.errorMessage, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                viewModel.deleteCompany(company.companyId)
            }
            .show()
    }
}