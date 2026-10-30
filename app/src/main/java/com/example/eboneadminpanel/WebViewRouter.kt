package com.example.superadmin

import android.content.Context
import android.content.Intent
import com.example.superadmin.core.CompanyDataHelper

object WebViewRouter {
    fun resolveZone(context: Context, zone: String?): String {
        if (!zone.isNullOrBlank()) return zone
        val currentComp = CompanyDataHelper.getCurrentCompanyId(context) ?: "Okara"
        return when {
            currentComp.contains("REN", true) || currentComp.contains("RENALA", true) -> "Renala"
            currentComp.contains("DEP", true) || currentComp.contains("DEPALPUR", true) -> "Depalpur"
            currentComp.contains("SAH", true) || currentComp.contains("SAHIWAL", true) -> "Sahiwal"
            currentComp.contains("PAK", true) || currentComp.contains("PAKPATTAN", true) -> "Pakpattan"
            currentComp.contains("ARI", true) || currentComp.contains("ARIFWALA", true) -> "Arifwala"
            currentComp.contains("GOJ", true) || currentComp.contains("GOJRA", true) -> "Gojra"
            currentComp.contains("HUJ", true) || currentComp.contains("HUJRA", true) -> "Hujra"
            currentComp.contains("PAT", true) || currentComp.contains("PATTOKI", true) -> "Pattoki"
            currentComp.contains("FSD", true) || currentComp.contains("FAISALABAD", true) -> "Faisalabad"
            currentComp.contains("LHR", true) || currentComp.contains("LAHORE", true) -> "Lahore"
            else -> "Okara"
        }
    }

    fun getTargetActivity(context: Context, isp: String?, zone: String?): Class<*> {
        val cleanIsp = isp?.uppercase() ?: "EBONE"
        val resolvedZone = resolveZone(context, zone)

        return when {
            cleanIsp == "EBONE" -> EboneWebViewActivity::class.java
            cleanIsp == "WATEEN" -> WateenWebViewActivity::class.java
            cleanIsp == "ZONG" && resolvedZone.equals("Renala", true) -> ZongRenalaWebViewActivity::class.java
            cleanIsp == "ZONG" -> ZongOkaraWebViewActivity::class.java
            cleanIsp == "JAZZ" -> JazzWebViewActivity::class.java
            else -> EboneWebViewActivity::class.java
        }
    }

    fun getTargetActivity(isp: String?, zone: String?): Class<*> {
        val cleanIsp = isp?.uppercase() ?: "EBONE"
        val cleanZone = if (zone.isNullOrBlank()) "Okara" else zone
        return when {
            cleanIsp == "EBONE" -> EboneWebViewActivity::class.java
            cleanIsp == "WATEEN" -> WateenWebViewActivity::class.java
            cleanIsp == "ZONG" && cleanZone.equals("Renala", true) -> ZongRenalaWebViewActivity::class.java
            cleanIsp == "ZONG" -> ZongOkaraWebViewActivity::class.java
            cleanIsp == "JAZZ" -> JazzWebViewActivity::class.java
            else -> EboneWebViewActivity::class.java
        }
    }

    fun launch(context: Context, isp: String?, zone: String?, extras: Intent.() -> Unit = {}) {
        val cleanIsp = isp?.uppercase() ?: "EBONE"
        val resolvedZone = resolveZone(context, zone)
        val intent = Intent(context, getTargetActivity(context, cleanIsp, resolvedZone))
        intent.putExtra("selected_isp", cleanIsp)
        intent.putExtra("target_zone", resolvedZone)
        intent.putExtra("zone", resolvedZone)
        intent.apply(extras)
        context.startActivity(intent)
    }
}
