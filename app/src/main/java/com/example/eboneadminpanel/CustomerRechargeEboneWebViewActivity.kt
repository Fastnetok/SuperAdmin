package com.example.superadmin

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.superadmin.core.CompanyDataHelper
import com.example.superadmin.core.SessionManager

/**
 * Dedicated, 100% Isolated WebView Activity for Ebone Customer Recharge & Package Activation.
 * Completely separate from Dealer Panel, Complaints, and Dashboard balance checks.
 */
class CustomerRechargeEboneWebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RechargeEboneWebView"
        private const val PREFS_NAME = "ebill_accounts"
    }

    private lateinit var webView: WebView
    private var targetZone = "DEP-001"
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_login)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            window.statusBarColor = Color.WHITE
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            window.statusBarColor = Color.parseColor("#E0E0E0")
        }

        val activeComp = SessionManager(this).getActiveCompanyId()
            ?: CompanyDataHelper.getCurrentCompanyId(this)
            ?: intent.getStringExtra("target_zone")
            ?: "DEP-001"
        targetZone = CompanyDataHelper.getCanonicalDocId(activeComp)
        searchQuery = intent.getStringExtra("customer_search_query")?.trim().orEmpty()

        findViewById<TextView>(R.id.tvHeaderTitle)?.text = "Ebone Customer Recharge ($targetZone)"

        webView = findViewById(R.id.loginWebView)

        findViewById<Button>(R.id.accountSwitchButton)?.visibility = View.GONE
        findViewById<ImageButton>(R.id.micButton)?.visibility = View.GONE

        configureWebView()
        loadInitialPage()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            saveFormData = true
            userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return
                CookieManager.getInstance().flush()

                val cookie = CookieManager.getInstance().getCookie("https://partner.ebill.pk")
                if (!cookie.isNullOrBlank()) {
                    saveSessionCookie(cookie)
                }

                if (url.contains("/login")) {
                    tryAutoLogin()
                } else if (url.contains("partner.ebill.pk")) {
                    if (!url.contains("/clients")) {
                        webView.loadUrl("https://partner.ebill.pk/clients")
                    } else if (searchQuery.isNotBlank()) {
                        performCustomerSearch(searchQuery)
                    } else {
                        focusSearchBox()
                    }
                }
            }
        }
    }

    private fun loadInitialPage() {
        val (username, password) = getCredentials()
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            webView.loadUrl("https://partner.ebill.pk/logincheck")
            return
        }

        val savedCookie = getSessionCookie()
        if (savedCookie.isNotBlank()) {
            savedCookie.split(";").forEach { part ->
                if (part.isNotBlank()) CookieManager.getInstance().setCookie("https://partner.ebill.pk", part.trim())
            }
            CookieManager.getInstance().flush()
            webView.loadUrl("https://partner.ebill.pk/clients")
        } else {
            webView.loadUrl("https://partner.ebill.pk/logincheck")
        }
    }

    private fun tryAutoLogin() {
        val (username, password) = getCredentials()
        if (username.isNullOrBlank() || password.isNullOrBlank()) return

        val safeU = JSONObjectEscape.forJavaScript(username)
        val safeP = JSONObjectEscape.forJavaScript(password)

        val script = """
            (function(){
                var u = document.querySelector('input[type=text], input[name=username], input[name=email], #username');
                var p = document.querySelector('input[type=password], #password');
                var b = document.querySelector('button[type=submit], input[type=submit], #send, .btn-login');
                if(!u || !p) return 'not_ready';
                u.value = '$safeU';
                u.dispatchEvent(new Event('input', {bubbles:true}));
                p.value = '$safeP';
                p.dispatchEvent(new Event('input', {bubbles:true}));
                if(b) b.click();
                return 'submitted';
            })()
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun focusSearchBox() {
        val script = """
            (function(){
                var sel = document.querySelector('select[name*="length"], select[name*="Table_length"], .dataTables_length select');
                if(sel){
                    sel.value = '1000';
                    if (sel.value !== '1000') {
                        var opt = document.createElement('option');
                        opt.value = '1000';
                        opt.text = '1000';
                        sel.appendChild(opt);
                        sel.value = '1000';
                    }
                    sel.dispatchEvent(new Event('change', {bubbles:true}));
                }
                var inp = document.querySelector('input[type=search], .dataTables_filter input, input[aria-controls*="clients"], #search, input[name=search_id]');
                if(inp){
                    inp.focus();
                    inp.scrollIntoView({behavior: 'smooth', block: 'center'});
                    return 'FOCUSED';
                }
                return 'NOT_FOUND';
            })()
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun performCustomerSearch(query: String) {
        val safeQ = JSONObjectEscape.forJavaScript(query)
        val script = """
            (function(){
                var sel = document.querySelector('select[name*="length"], select[name*="Table_length"], .dataTables_length select');
                if(sel){
                    sel.value = '1000';
                    if (sel.value !== '1000') {
                        var opt = document.createElement('option');
                        opt.value = '1000';
                        opt.text = '1000';
                        sel.appendChild(opt);
                        sel.value = '1000';
                    }
                    sel.dispatchEvent(new Event('change', {bubbles:true}));
                }
                var inp = document.querySelector('input[type=search], .dataTables_filter input, input[aria-controls*="clients"], #search, input[name=search_id]');
                if(inp){
                    inp.focus();
                    inp.value = '$safeQ';
                    inp.dispatchEvent(new Event('input', {bubbles:true}));
                    inp.dispatchEvent(new Event('change', {bubbles:true}));
                    inp.dispatchEvent(new Event('keyup', {bubbles:true}));
                    return 'SEARCH_FILLED';
                }
                return 'SEARCH_INPUT_NOT_FOUND';
            })()
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun saveSessionCookie(cookie: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("EBONE_$targetZone", cookie).apply()
    }

    private fun getCredentials(): Pair<String?, String?> {
        val dealerPair = IspPanelSettingsActivity.getSavedDealerAccount(this, "EBONE", targetZone)
        if (dealerPair != null && !dealerPair.first.isNullOrBlank() && !dealerPair.second.isNullOrBlank()) {
            return dealerPair
        }
        val u = IspPanelSettingsActivity.getSavedUsername(this, "EBONE", targetZone)
        val p = IspPanelSettingsActivity.getSavedPassword(this, "EBONE", targetZone)
        return u to p
    }

    private fun getSessionCookie(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("EBONE_$targetZone", "") ?: ""
    }

    private object JSONObjectEscape {
        fun forJavaScript(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
    }
}
