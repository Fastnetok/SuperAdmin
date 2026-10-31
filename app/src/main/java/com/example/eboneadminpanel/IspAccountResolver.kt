package com.example.superadmin

import android.content.Context

/**
 * Centralized Manager for resolving active ISP accounts (Franchise / Dealer)
 * based on Zone, ISP, and Module Permissions ("New Complaints", "Customer Package Activation", etc.).
 */
object IspAccountResolver {

    fun resolveModule(manualAction: String?, autoActivateCustomerId: String?): String {
        val action = manualAction?.uppercase() ?: ""
        return when {
            action == "CUSTOMER_ACTIVATION" || action == "RELIEF_VIEW" || action == "PACKAGE_ACTIVATION" || action == "RECHARGE" -> "Customer Package Activation"
            action == "UNPAID_TODAY" -> "Unpaid Today List"
            action == "CHECK_BALANCE" || action == "DEALER_TOPUP" -> "Franchise Balance & Dealer TopUp"
            action == "RESOLVE_MONITOR" || action == "NEW_COMPLAINTS" -> "New Complaints"
            autoActivateCustomerId != null -> "New Complaints"
            else -> "New Complaints"
        }
    }

    fun getActiveUsername(context: Context, isp: String, zone: String, requiredModule: String?): String? {
        val direct = IspPanelSettingsActivity.getSavedUsername(context, isp, zone, requiredModule)
        if (!direct.isNullOrEmpty()) return direct

        val canonicalZone = com.example.superadmin.core.CompanyDataHelper.getCanonicalDocId(zone)
        if (!canonicalZone.equals(zone, ignoreCase = true)) {
            val canonicalUser = IspPanelSettingsActivity.getSavedUsername(context, isp, canonicalZone, requiredModule)
            if (!canonicalUser.isNullOrEmpty()) return canonicalUser
        }

        return IspPanelSettingsActivity.getSavedAccount(context, isp, "ALL", requiredModule)?.first
    }

    fun getActivePassword(context: Context, isp: String, zone: String, requiredModule: String?): String? {
        val direct = IspPanelSettingsActivity.getSavedPassword(context, isp, zone, requiredModule)
        if (!direct.isNullOrEmpty()) return direct

        val canonicalZone = com.example.superadmin.core.CompanyDataHelper.getCanonicalDocId(zone)
        if (!canonicalZone.equals(zone, ignoreCase = true)) {
            val canonicalPass = IspPanelSettingsActivity.getSavedPassword(context, isp, canonicalZone, requiredModule)
            if (!canonicalPass.isNullOrEmpty()) return canonicalPass
        }

        return IspPanelSettingsActivity.getSavedAccount(context, isp, "ALL", requiredModule)?.second
    }
}
