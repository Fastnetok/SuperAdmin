package com.example.superadmin.superadmin.companymanager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.superadmin.R
import com.example.superadmin.models.Company

class CompanyAdapter(
    private var companies: List<Company>,
    private val onEditClick: (Company) -> Unit,
    private val onDeleteClick: (Company) -> Unit
) : RecyclerView.Adapter<CompanyAdapter.CompanyViewHolder>() {

    class CompanyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCompanyName: TextView = view.findViewById(R.id.tvCompanyName)
        val tvCompanyId: TextView = view.findViewById(R.id.tvCompanyId)
        val tvCompanyCity: TextView = view.findViewById(R.id.tvCompanyCity)
        val tvLicenseStatus: TextView = view.findViewById(R.id.tvLicenseStatus)
        val tvLicenseLimits: TextView = view.findViewById(R.id.tvLicenseLimits)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CompanyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_company, parent, false)
        return CompanyViewHolder(view)
    }

    override fun onBindViewHolder(holder: CompanyViewHolder, position: Int) {
        val company = companies[position]
        holder.tvCompanyName.text = company.companyName
        holder.tvCompanyId.text = "ID: ${company.companyId}"
        holder.tvCompanyCity.text = "City: ${company.city}"
        holder.tvLicenseStatus.text = company.licenseStatus
        holder.tvLicenseLimits.text = "Mgr: ${company.maxManagers} | Sup: ${company.maxSupervisors} | Emp: ${company.maxEmployees} | Cust: ${company.maxCustomers} | Dlr: ${company.maxDealers}"

        holder.itemView.setOnClickListener { onEditClick(company) }
        holder.itemView.setOnLongClickListener {
            onDeleteClick(company)
            true
        }
    }

    override fun getItemCount(): Int = companies.size

    fun updateList(newCompanies: List<Company>) {
        companies = newCompanies
        notifyDataSetChanged()
    }
}