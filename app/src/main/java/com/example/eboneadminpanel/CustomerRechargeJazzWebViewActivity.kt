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
 * Dedicated, 100% Isolated WebView Activity for Jazz Customer Recharge & Package Activation.
 * Completely separate from Dealer Panel, Complaints, and Dashboard balance checks.
 */
class CustomerRechargeJazzWebViewActivity : AppCompatActivity() {

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

        findViewById<TextView>(R.id.tvHeaderTitle)?.text = "Jazz Customer Recharge ($targetZone)"

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

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return
                CookieManager.getInstance().flush()

                if (url.contains("login")) {
                    tryAutoLogin()
                } else if (url.contains("jazzftth.pk") || url.contains("biz.jazzftth.pk")) {
                    if (searchQuery.isNotBlank()) {
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
            webView.loadUrl("https://biz.jazzftth.pk/users/login")
            return
        }

        webView.loadUrl("https://biz.jazzftth.pk/users/login")
    }

    private var jazzLoginAttempt = 1

    private fun tryAutoLogin() {
        val (username, password) = getCredentials()
        if (username.isNullOrBlank() || password.isNullOrBlank()) return

        val safeU = JSONObjectEscape.forJavaScript(username)
        val safeP = JSONObjectEscape.forJavaScript(password)

        val parseScript = """
            (function() { 
                var f = document.getElementById('first'); 
                var s = document.getElementById('second'); 
                return (f ? f.innerText.trim() : '') + ',' + (s ? s.innerText.trim() : ''); 
            })();
        """.trimIndent()

        webView.evaluateJavascript(parseScript) { res ->
            val cleaned = res?.replace("\"", "") ?: ""
            val parts = cleaned.split(",")
            val sum = if (parts.size == 2 && parts[0].toIntOrNull() != null && parts[1].toIntOrNull() != null) {
                parts[0].toInt() + parts[1].toInt()
            } else 0

            val script = """
                (function(){
                    var u = document.querySelector('#user_login, input[type=text], input[name=username]');
                    var p = document.querySelector('#user_pass, input[type=password]');
                    var ans = document.querySelector('#user, input[name=captcha_ans]');
                    var sumF = document.querySelector('#sum');
                    var b = document.querySelector('#login_btn, button[type=submit], input[type=submit]');
                    if(!u || !p) return 'not_ready';
                    u.value = '$safeU';
                    u.dispatchEvent(new Event('input', {bubbles:true}));
                    p.value = '$safeP';
                    p.dispatchEvent(new Event('input', {bubbles:true}));
                    if(ans && $sum > 0) {
                        ans.value = '$sum';
                        ans.dispatchEvent(new Event('input', {bubbles:true}));
                        ans.dispatchEvent(new Event('keyup', {bubbles:true}));
                    }
                    if(sumF && $sum > 0) { sumF.value = '$sum'; }
                    if(b) b.click();
                    return 'submitted';
                })()
            """.trimIndent()

            webView.evaluateJavascript(script) {
                webView.postDelayed({
                    val currentUrl = webView.url.orEmpty().lowercase()
                    if (currentUrl.contains("login")) {
                        webView.evaluateJavascript("(function(){ return document.getElementById('user_login') !== null; })()") { stillOnLogin ->
                            val onLogin = stillOnLogin?.trim() == "true"
                            if (onLogin && jazzLoginAttempt < 2) {
                                jazzLoginAttempt++
                                tryAutoLogin()
                            }
                        }
                    }
                }, 2500)
            }
        }
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
                var inp = document.querySelector('input[type=search], input[name=search], #search, input[placeholder*="Search"]');
                if(inp){
                    inp.focus();
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
                var inp = document.querySelector('input[type=search], input[name=search], #search, input[placeholder*="Search"]');
                if(inp){
                    inp.focus();
                    inp.value = '$safeQ';
                    inp.dispatchEvent(new Event('input', {bubbles:true}));
                    inp.dispatchEvent(new Event('change', {bubbles:true}));
                    return 'SEARCH_FILLED';
                }
                return 'SEARCH_INPUT_NOT_FOUND';
            })()
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun getCredentials(): Pair<String?, String?> {
        val activeCompRaw = SessionManager(this).getActiveCompanyId()
            ?: CompanyDataHelper.getCurrentCompanyId(this)
            ?: targetZone
        val canonicalZone = CompanyDataHelper.getCanonicalDocId(activeCompRaw)

        val u = IspPanelSettingsActivity.getSavedUsername(this, "JAZZ", targetZone)
            ?: IspPanelSettingsActivity.getSavedUsername(this, "JAZZ", canonicalZone)
            ?: IspPanelSettingsActivity.getSavedUsername(this, "JAZZ", activeCompRaw)
            ?: IspPanelSettingsActivity.getSavedUsername(this, "JAZZ", "DEP-001")
            ?: IspPanelSettingsActivity.getSavedUsername(this, "JAZZ", "OKA-001")
        val p = IspPanelSettingsActivity.getSavedPassword(this, "JAZZ", targetZone)
            ?: IspPanelSettingsActivity.getSavedPassword(this, "JAZZ", canonicalZone)
            ?: IspPanelSettingsActivity.getSavedPassword(this, "JAZZ", activeCompRaw)
            ?: IspPanelSettingsActivity.getSavedPassword(this, "JAZZ", "DEP-001")
            ?: IspPanelSettingsActivity.getSavedPassword(this, "JAZZ", "OKA-001")
        return u to p
    }

    private object JSONObjectEscape {
        fun forJavaScript(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
    }
}
