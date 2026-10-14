package com.example.superadmin.superadmin.companymanager

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.superadmin.models.Company

object CompanyResetHelper {

    fun promptTestReset(activity: AppCompatActivity, company: Company, viewModel: CompanyViewModel) {
        AlertDialog.Builder(activity)
            .setTitle("Testing Data Reset (${company.companyId})")
            .setMessage("Kya aap Company '${company.companyName}' (${company.companyId}) ka tamam testing data (employees, approved devices, attendance, complaints) clear karna chahte hain?\n\nCompany Profile active rahe gi lekin pehla testing data saaf ho jaye ga taake aap dobara fresh testing kar sakain.")
            .setPositiveButton("Yes, Reset Data") { dialog, _ ->
                dialog.dismiss()
                viewModel.resetCompanyTestData(company)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }
}
