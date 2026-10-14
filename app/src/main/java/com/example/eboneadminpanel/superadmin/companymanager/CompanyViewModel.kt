package com.example.superadmin.superadmin.companymanager

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.superadmin.core.Constants
import com.example.superadmin.models.Company
import com.example.superadmin.repositories.CompanyRepository
import com.example.superadmin.utils.CompanyIdGenerator

class CompanyViewModel : ViewModel() {

    private val repository = CompanyRepository()

    private val _companies = MutableLiveData<List<Company>>()
    val companies: LiveData<List<Company>> = _companies

    private val _operationResult = MutableLiveData<String>()
    val operationResult: LiveData<String> = _operationResult

    fun loadCompanies() {
        repository.getAllCompanies(object : CompanyRepository.CompanyListCallback {
            override fun onSuccess(companies: List<Company>) {
                _companies.value = companies
                repository.cleanOrphanedData()
            }

            override fun onError(message: String) {
                _operationResult.value = "Error: $message"
            }
        })
    }

    fun addNewCompany(
        companyName: String,
        city: String,
        ownerName: String,
        adminEmail: String,
        adminPassword: String,
        maxManagers: Int,
        maxSupervisors: Int,
        maxEmployees: Int,
        maxCustomers: Int,
        maxDealers: Int,
        licenseStatus: String,
        licenseExpiry: Long
    ) {
        val cleanEmail = adminEmail.trim().lowercase()

        if (cleanEmail.isNotEmpty()) {
            repository.checkEmailExists(cleanEmail, null, object : CompanyRepository.EmailCheckCallback {
                override fun onResult(exists: Boolean) {
                    if (exists) {
                        _operationResult.value = "Error: Yeh email ($cleanEmail) pehle se kisi company ke paas registered hai!"
                        return
                    }
                    proceedAddingCompany(
                        companyName, city, ownerName, cleanEmail, adminPassword,
                        maxManagers, maxSupervisors, maxEmployees, maxCustomers, maxDealers,
                        licenseStatus, licenseExpiry
                    )
                }

                override fun onError(message: String) {
                    _operationResult.value = "Error: $message"
                }
            })
        } else {
            proceedAddingCompany(
                companyName, city, ownerName, cleanEmail, adminPassword,
                maxManagers, maxSupervisors, maxEmployees, maxCustomers, maxDealers,
                licenseStatus, licenseExpiry
            )
        }
    }

    private fun proceedAddingCompany(
        companyName: String,
        city: String,
        ownerName: String,
        cleanEmail: String,
        adminPassword: String,
        maxManagers: Int,
        maxSupervisors: Int,
        maxEmployees: Int,
        maxCustomers: Int,
        maxDealers: Int,
        licenseStatus: String,
        licenseExpiry: Long
    ) {
        val cityPrefix = Constants.CITY_PREFIXES[city] ?: city.take(3).uppercase()

        CompanyIdGenerator.generateNextId(cityPrefix, object : CompanyIdGenerator.IdGeneratedCallback {
            override fun onIdGenerated(companyId: String) {
                val newCompany = Company(
                    companyId = companyId,
                    companyName = companyName,
                    city = city,
                    ownerName = ownerName,
                    adminEmail = cleanEmail,
                    adminPassword = adminPassword,
                    maxManagers = maxManagers,
                    maxSupervisors = maxSupervisors,
                    maxEmployees = maxEmployees,
                    maxCustomers = maxCustomers,
                    maxDealers = maxDealers,
                    licenseStatus = licenseStatus,
                    licenseExpiry = licenseExpiry,
                    createdAt = System.currentTimeMillis()
                )

                repository.addCompany(newCompany, object : CompanyRepository.SimpleCallback {
                    override fun onSuccess() {
                        _operationResult.value = "Company '$companyName' successfully add ho gayi ($companyId)"
                        loadCompanies()
                    }

                    override fun onError(message: String) {
                        _operationResult.value = "Error: $message"
                    }
                })
            }

            override fun onError(message: String) {
                _operationResult.value = "Error: $message"
            }
        })
    }

    fun updateCompany(company: Company) {
        val cleanEmail = company.adminEmail.trim().lowercase()
        val companyToSave = company.copy(adminEmail = cleanEmail)

        if (cleanEmail.isNotEmpty()) {
            repository.checkEmailExists(cleanEmail, companyToSave.companyId, object : CompanyRepository.EmailCheckCallback {
                override fun onResult(exists: Boolean) {
                    if (exists) {
                        _operationResult.value = "Error: Yeh email ($cleanEmail) pehle se kisi doosri company ke paas registered hai!"
                        return
                    }
                    repository.updateCompany(companyToSave, object : CompanyRepository.SimpleCallback {
                        override fun onSuccess() {
                            _operationResult.value = "Company update ho gayi"
                            loadCompanies()
                        }

                        override fun onError(message: String) {
                            _operationResult.value = "Error: $message"
                        }
                    })
                }

                override fun onError(message: String) {
                    _operationResult.value = "Error: $message"
                }
            })
        } else {
            repository.updateCompany(companyToSave, object : CompanyRepository.SimpleCallback {
                override fun onSuccess() {
                    _operationResult.value = "Company update ho gayi"
                    loadCompanies()
                }

                override fun onError(message: String) {
                    _operationResult.value = "Error: $message"
                }
            })
        }
    }

    fun deleteCompany(companyId: String) {
        repository.deleteCompany(companyId, object : CompanyRepository.SimpleCallback {
            override fun onSuccess() {
                _operationResult.value = "Company delete ho gayi"
                loadCompanies()
            }

            override fun onError(message: String) {
                _operationResult.value = "Error: $message"
            }
        })
    }

    fun resetCompanyTestData(company: Company) {
        repository.resetCompanyTestData(company.companyId, object : CompanyRepository.SimpleCallback {
            override fun onSuccess() {
                _operationResult.value = "Company '${company.companyName}' (${company.companyId}) ka testing data reset ho gaya hai."
                loadCompanies()
            }

            override fun onError(message: String) {
                _operationResult.value = "Error: $message"
            }
        })
    }
}