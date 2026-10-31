package com.example.superadmin

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.superadmin.core.CompanyDataHelper
import com.example.superadmin.core.SessionManager

/**
 * Dedicated, 100% Isolated WebView Activity for Zong Customer Recharge & Package Activation.
 * Completely separate from Dealer Panel, Complaints, and Dashboard balance checks.
 */
class CustomerRechargeZongWebViewActivity : AppCompatActivity() {

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

        findViewById<TextView>(R.id.tvHeaderTitle)?.text = "Zong Customer Recharge ($targetZone)"

        webView = findViewById(R.id.loginWebView)

        findViewById<Button>(R.id.accountSwitchButton)?.visibility = View.GONE
        findViewById<android.widget.ImageButton>(R.id.micButton)?.visibility = View.GONE

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

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                if (message != null && (message.contains("Data Tables", ignoreCase = true) || message.contains("DataTables", ignoreCase = true) || message.contains("Invalid JSON", ignoreCase = true))) {
                    result?.confirm()
                    return true
                }
                return super.onJsAlert(view, url, message, result)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                suppressDataTablesAlerts()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return
                CookieManager.getInstance().flush()

                suppressDataTablesAlerts()

                val cookie = CookieManager.getInstance().getCookie("https://turbonet.zong.com.pk")
                if (!cookie.isNullOrBlank()) {
                    saveSessionCookie(cookie)
                }

                if (url.contains("login.php") || url.contains("login")) {
                    tryAutoLogin()
                } else if (url.contains("zong.com.pk") || url.contains("turbonet")) {
                    if (!url.contains("customers.php") && !url.contains("customer_portal.php")) {
                        webView.loadUrl("https://turbonet.zong.com.pk/customers.php")
                    } else if (searchQuery.isNotBlank()) {
                        performCustomerSearch(searchQuery)
                    } else {
                        focusSearchBox()
                    }
                }
            }
        }
    }

    private fun suppressDataTablesAlerts() {
        val script = """
            (function(){
                window.alert = function(m){ console.log('Suppressed alert: ' + m); };
                if (window.jQuery && $.fn && $.fn.dataTable) {
                    $.fn.dataTable.ext.errMode = 'none';
                }
            })()
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun loadInitialPage() {
        val (username, password) = getCredentials()
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            webView.loadUrl("https://turbonet.zong.com.pk/login.php")
            return
        }

        val savedCookie = getSessionCookie()
        if (savedCookie.isNotBlank()) {
            savedCookie.split(";").forEach { part ->
                if (part.isNotBlank()) CookieManager.getInstance().setCookie("https://turbonet.zong.com.pk", part.trim())
            }
            CookieManager.getInstance().flush()
            webView.loadUrl("https://turbonet.zong.com.pk/customers.php")
        } else {
            webView.loadUrl("https://turbonet.zong.com.pk/login.php")
        }
    }

    private var zongLoginAttempt = 1

    private fun tryAutoLogin() {
        val currentUrl = webView.url.orEmpty().lowercase()
        if (!currentUrl.contains("login.php") && !currentUrl.contains("login") && currentUrl.contains("zong.com.pk")) {
            return
        }

        val (username, password) = getCredentials()
        if (username.isNullOrBlank() || password.isNullOrBlank()) return

        val safeU = JSONObjectEscape.forJavaScript(username)
        val safeP = JSONObjectEscape.forJavaScript(password)

        val script = """
            (function(){
                var u = document.querySelector('input[type=text], input[name=username], input[name=email], #username');
                var p = document.querySelector('input[type=password], #password');
                var b = document.querySelector('button[type=submit], input[type=submit], #send, .btn-login, #login-btn');
                if(!u || !p) return 'not_ready';
                u.focus();
                u.value = '$safeU';
                u.dispatchEvent(new Event('input', {bubbles:true}));
                u.dispatchEvent(new Event('change', {bubbles:true}));
                p.focus();
                p.value = '$safeP';
                p.dispatchEvent(new Event('input', {bubbles:true}));
                p.dispatchEvent(new Event('change', {bubbles:true}));
                if(b) b.click();
                return 'submitted';
            })()
        """.trimIndent()

        webView.evaluateJavascript(script) { res ->
            if (res?.contains("submitted") == true) {
                webView.postDelayed({
                    val urlAfter = webView.url.orEmpty().lowercase()
                    if (urlAfter.contains("login") && zongLoginAttempt < 2) {
                        zongLoginAttempt++
                        tryAutoLogin()
                    }
                }, 2000)
            }
        }
    }

    private fun focusSearchBox() {
        val script = """
            (function(){
                window.alert = function(m){ console.log('Suppressed alert: ' + m); };
                if (window.jQuery && $.fn && $.fn.dataTable) {
                    $.fn.dataTable.ext.errMode = 'none';
                }
                var sel = document.querySelector('select[name="managercustomers_length"], select[name*="length"], .dataTables_length select');
                if(sel){
                    sel.value = '25';
                    sel.dispatchEvent(new Event('change', {bubbles:true}));
                }
                var inp = document.querySelector('input[aria-controls="managercustomers"], .dataTables_filter input, input[type=search], #search, input[name=username]');
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
                window.alert = function(m){ console.log('Suppressed alert: ' + m); };
                if (window.jQuery && $.fn && $.fn.dataTable) {
                    $.fn.dataTable.ext.errMode = 'none';
                }
                var sel = document.querySelector('select[name="managercustomers_length"], select[name*="length"], .dataTables_length select');
                if(sel){
                    sel.value = '25';
                    sel.dispatchEvent(new Event('change', {bubbles:true}));
                }
                var inp = document.querySelector('input[aria-controls="managercustomers"], .dataTables_filter input, input[type=search], #search, input[name=username]');
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
        getSharedPreferences("zong_accounts_prefs", MODE_PRIVATE).edit().putString("ZONG_$targetZone", cookie).apply()
    }

    private fun getSessionCookie(): String {
        return getSharedPreferences("zong_accounts_prefs", MODE_PRIVATE).getString("ZONG_$targetZone", "") ?: ""
    }

    private fun getCredentials(): Pair<String?, String?> {
        val u = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", targetZone)
        val p = IspPanelSettingsActivity.getSavedPassword(this, "ZONG", targetZone)
        return u to p
    }

    private object JSONObjectEscape {
        fun forJavaScript(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
    }
}
