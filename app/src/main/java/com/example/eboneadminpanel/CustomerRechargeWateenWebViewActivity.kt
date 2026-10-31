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
 * Dedicated, 100% Isolated WebView Activity for Wateen Customer Recharge & Package Activation.
 * Completely separate from Dealer Panel, Complaints, and Dashboard balance checks.
 */
class CustomerRechargeWateenWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var targetZone = "DEP-001"
    private var searchQuery = ""
    private var searchExecuted = false

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

        findViewById<TextView>(R.id.tvHeaderTitle)?.text = "Wateen Customer Recharge ($targetZone)"

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
                webView.evaluateJavascript("(function(){ window.alert = function(m){ console.log('Suppressed alert: ' + m); }; })()", null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return
                CookieManager.getInstance().flush()

                webView.evaluateJavascript("(function(){ window.alert = function(m){ console.log('Suppressed alert: ' + m); }; })()", null)

                val cookie = CookieManager.getInstance().getCookie("https://panel.wateen.com")
                if (!cookie.isNullOrBlank()) {
                    saveSessionCookie(cookie)
                }

                if (url.contains("auth.html") || url.contains("login")) {
                    searchExecuted = false
                    tryAutoLogin()
                } else if (url.contains("panel.wateen.com") || url.contains("wateen.com")) {
                    if (!url.contains("/user/user/all") && !url.contains("/user/user/online")) {
                        webView.loadUrl("https://panel.wateen.com/user/user/all")
                    } else if (!searchExecuted) {
                        searchExecuted = true
                        if (searchQuery.isNotBlank()) {
                            performCustomerSearch(searchQuery)
                        } else {
                            focusSearchBox()
                        }
                    }
                }
            }
        }
    }

    private fun loadInitialPage() {
        val (username, password) = getCredentials()
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            webView.loadUrl("https://panel.wateen.com/auth.html")
            return
        }

        val savedCookie = getSessionCookie()
        if (savedCookie.isNotBlank()) {
            savedCookie.split(";").forEach { part ->
                if (part.isNotBlank()) CookieManager.getInstance().setCookie("https://panel.wateen.com", part.trim())
            }
            CookieManager.getInstance().flush()
            webView.loadUrl("https://panel.wateen.com/user/user/all")
        } else {
            webView.loadUrl("https://panel.wateen.com/auth.html")
        }
    }

    private fun tryAutoLogin() {
        val (username, password) = getCredentials()
        if (username.isNullOrBlank() || password.isNullOrBlank()) return

        val safeU = JSONObjectEscape.forJavaScript(username)
        val safeP = JSONObjectEscape.forJavaScript(password)

        val script = """
            (function(){
                var u = document.querySelector('input[type=text], input[name=username], input[name=email], #username, #email');
                var p = document.querySelector('input[type=password], #password, input[name=password]');
                var b = document.querySelector('button[type=submit], input[type=submit], #send, .btn-login, #login_btn, #btn-login');
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
        webView.evaluateJavascript(script, null)
    }

    private fun focusSearchBox() {
        val script = """
            (function(){
                var sel = document.querySelector('select[name*="length"], select[name*="Table_length"], .dataTables_length select');
                if(sel){
                    sel.value = '20';
                    if (sel.value !== '20') {
                        var opt = document.createElement('option');
                        opt.value = '20';
                        opt.text = '20';
                        sel.appendChild(opt);
                        sel.value = '20';
                    }
                    sel.dispatchEvent(new Event('change', {bubbles:true}));
                }
                var inp = document.querySelector('input[type=search], input[name=search], input[name=customer_id], #search, input[placeholder*="Search"], input[placeholder*="search"]');
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
                    sel.value = '20';
                    if (sel.value !== '20') {
                        var opt = document.createElement('option');
                        opt.value = '20';
                        opt.text = '20';
                        sel.appendChild(opt);
                        sel.value = '20';
                    }
                    sel.dispatchEvent(new Event('change', {bubbles:true}));
                }
                var inp = document.querySelector('input[type=search], input[name=search], input[name=customer_id], #search, input[placeholder*="Search"], input[placeholder*="search"]');
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
        val activeCompRaw = SessionManager(this).getActiveCompanyId() ?: targetZone
        val cleanComp = CompanyDataHelper.getCanonicalDocId(activeCompRaw)
        getSharedPreferences("wateen_accounts", MODE_PRIVATE).edit()
            .putString("WATEEN_$cleanComp", cookie)
            .putString("WATEEN_$targetZone", cookie)
            .apply()
    }

    private fun getSessionCookie(): String {
        val activeCompRaw = SessionManager(this).getActiveCompanyId() ?: targetZone
        val cleanComp = CompanyDataHelper.getCanonicalDocId(activeCompRaw)
        val sp = getSharedPreferences("wateen_accounts", MODE_PRIVATE)
        val c1 = sp.getString("WATEEN_$cleanComp", null)
        if (!c1.isNullOrBlank()) return c1
        return sp.getString("WATEEN_$targetZone", "") ?: ""
    }

    private fun getCredentials(): Pair<String?, String?> {
        val activeCompRaw = SessionManager(this).getActiveCompanyId()
            ?: CompanyDataHelper.getCurrentCompanyId(this)
            ?: targetZone
        val canonicalZone = CompanyDataHelper.getCanonicalDocId(activeCompRaw)

        val u = IspPanelSettingsActivity.getSavedUsername(this, "WATEEN", targetZone)
            ?: IspPanelSettingsActivity.getSavedUsername(this, "WATEEN", canonicalZone)
            ?: IspPanelSettingsActivity.getSavedUsername(this, "WATEEN", activeCompRaw)
            ?: IspPanelSettingsActivity.getSavedUsername(this, "WATEEN", "DEP-001")
            ?: IspPanelSettingsActivity.getSavedUsername(this, "WATEEN", "OKA-001")
        val p = IspPanelSettingsActivity.getSavedPassword(this, "WATEEN", targetZone)
            ?: IspPanelSettingsActivity.getSavedPassword(this, "WATEEN", canonicalZone)
            ?: IspPanelSettingsActivity.getSavedPassword(this, "WATEEN", activeCompRaw)
            ?: IspPanelSettingsActivity.getSavedPassword(this, "WATEEN", "DEP-001")
            ?: IspPanelSettingsActivity.getSavedPassword(this, "WATEEN", "OKA-001")
        return u to p
    }

    private object JSONObjectEscape {
        fun forJavaScript(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
    }
}
