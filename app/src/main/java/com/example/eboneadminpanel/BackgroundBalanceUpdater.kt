package com.example.superadmin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Headless helper to update franchise balances in the background.
 */
object BackgroundBalanceUpdater {
    private const val TAG = "BgBalanceUpdater"
    private const val ISP_SESSION_PREFS = "isp_session_cookies"

    @SuppressLint("SetJavaScriptEnabled")
    fun checkBalance(
        context: Context,
        isp: String,
        zone: String,
        onComplete: (Double?) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        var isFinished = false

        val timeoutRunnable = Runnable {
            if (!isFinished) {
                isFinished = true
                Log.e(TAG, "Timeout checking balance for $isp/$zone")
                onComplete(null)
            }
        }
        val timeoutMs = if (isp.uppercase() == "WATEEN") 35000L else 20000L
        handler.postDelayed(timeoutRunnable, timeoutMs)

        var username = IspPanelSettingsActivity.getSavedFranchiseAccount(context, isp, zone)?.first
            ?: IspPanelSettingsActivity.getSavedUsername(context, isp, zone)
        var password = IspPanelSettingsActivity.getSavedFranchiseAccount(context, isp, zone)?.second
            ?: IspPanelSettingsActivity.getSavedPassword(context, isp, zone)
        var accountCookie = ""

        val cleanZone = zone.trim().lowercase().replace("-", "_")
        val prefName = when (isp.uppercase()) {
            "EBONE" -> "ebill_accounts_$cleanZone"
            "WATEEN" -> "wateen_accounts_$cleanZone"
            "ZONG" -> "zong_accounts_$cleanZone"
            "JAZZ" -> "jazz_accounts_$cleanZone"
            else -> "${isp.lowercase()}_accounts_$cleanZone"
        }
        try {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val sp = EncryptedSharedPreferences.create(context, prefName, masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            val raw = sp.getString("accounts_json", "") ?: ""
            if (raw.isNotEmpty()) {
                val jsonObj = JSONObject(raw)
                val active = sp.getString("active_account", "") ?: ""
                val key = if (active.isNotEmpty() && jsonObj.has(active)) active else if (jsonObj.keys().hasNext()) jsonObj.keys().next() else null
                if (key != null) {
                    val acc = jsonObj.getJSONObject(key)
                    val u = acc.optString("username", "")
                    val p = acc.optString("password", "")
                    val c = acc.optString("cookie", "")
                    if (u.isNotEmpty() && p.isNotEmpty()) {
                        if (username.isNullOrEmpty()) username = u
                        if (password.isNullOrEmpty()) password = p
                    }
                    if (c.isNotEmpty()) {
                        accountCookie = c
                    }
                }
            }
        } catch (_: Exception) {}

        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            Log.e(TAG, "Credentials not found for $isp/$zone")
            isFinished = true
            handler.removeCallbacks(timeoutRunnable)
            onComplete(null)
            return
        }

        val generalCookie = getIspSessionCookie(context, isp, zone)
        val savedCookie = if (accountCookie.isNotEmpty()) accountCookie else generalCookie

        val initialUrl = if (savedCookie.isNotEmpty()) {
            when (isp.uppercase()) {
                "WATEEN" -> "https://panel.wateen.com/user/user/all"
                "ZONG" -> "https://turbonet.zong.com.pk/customers.php"
                "JAZZ" -> "https://biz.jazzftth.pk/dashboard"
                else -> "https://partner.ebill.pk/clients"
            }
        } else {
            when (isp.uppercase()) {
                "WATEEN" -> "https://panel.wateen.com/auth.html"
                "ZONG" -> "https://turbonet.zong.com.pk/login.php"
                "JAZZ" -> "https://biz.jazzftth.pk/users/login"
                else -> "https://partner.ebill.pk/logincheck"
            }
        }

        handler.post {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.loadsImagesAutomatically = true
            webView.measure(1080, 1920)
            webView.layout(0, 0, 1080, 1920)
            
            // Set User Agent
            if (isp.uppercase() == "WATEEN") {
                webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            } else {
                webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 6.0) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            }

            // Restore Cookies
            val domain = when (isp.uppercase()) {
                "WATEEN" -> "https://panel.wateen.com"
                "ZONG" -> "https://turbonet.zong.com.pk"
                "JAZZ" -> "https://biz.jazzftth.pk"
                else -> "https://partner.ebill.pk"
            }

            // Isolated cleanup before restoring session cookie
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(webView, true)
            cm.removeAllCookies(null)
            cm.flush()

            if (savedCookie.isNotEmpty()) {
                savedCookie.split(";").forEach { part ->
                    if (part.isNotBlank()) cm.setCookie(domain, part.trim())
                }
                cm.flush()
            }

            webView.webViewClient = object : WebViewClient() {
                var loginAttempted = false
                var balanceReadAttempted = false
                var readRetryCount = 0

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (isFinished || url == null) return
                    
                    val currentDomain = when (isp.uppercase()) {
                        "WATEEN" -> "https://panel.wateen.com"
                        "ZONG" -> "https://turbonet.zong.com.pk"
                        "JAZZ" -> "https://biz.jazzftth.pk"
                        else -> "https://partner.ebill.pk"
                    }
                    val currentCookie = CookieManager.getInstance().getCookie(currentDomain)
                    if (!currentCookie.isNullOrEmpty()) {
                        saveIspSessionCookie(context, isp, zone, currentCookie)
                    }

                    if (url.contains("login") || url.contains("auth.html") || url.contains("users/login")) {
                        if (!loginAttempted) {
                            loginAttempted = true
                            Log.d(TAG, "Attempting background login for $isp/$zone")
                            val script = """
                                (function(){
                                    var u = document.querySelector('input[name=username], input[name=email], input[name=user], #username, input[type=text]');
                                    var p = document.querySelector('input[name=password], input[name=pass], #password, input[type=password]');
                                    var b = document.querySelector('button[type=submit], input[type=submit], #send, .btn-primary, .btn-login, button');
                                    if(u && p){
                                        u.value = '$username';
                                        u.dispatchEvent(new Event('input', {bubbles: true}));
                                        u.dispatchEvent(new Event('change', {bubbles: true}));
                                        p.value = '$password';
                                        p.dispatchEvent(new Event('input', {bubbles: true}));
                                        p.dispatchEvent(new Event('change', {bubbles: true}));
                                        if(b){ b.click(); }
                                        else {
                                            var f = u.closest('form');
                                            if(f) f.submit();
                                        }
                                        return 'clicked';
                                    }
                                    return 'fields_not_found';
                                })()
                            """.trimIndent()
                            webView.evaluateJavascript(script) { res ->
                                Log.d(TAG, "Login script result: $res")
                            }
                        }
                    } else {
                        // On Dashboard or Inner Page
                        if (!balanceReadAttempted) {
                            readBalance(isp, zone, webView) { balance ->
                                if (balance != null) {
                                    balanceReadAttempted = true
                                    isFinished = true
                                    handler.removeCallbacks(timeoutRunnable)
                                    webView.postDelayed({ webView.destroy() }, 200L)
                                    onComplete(balance)
                                } else {
                                    // Retry logic for dynamic rendering
                                    val maxRetries = if (isp.uppercase() == "WATEEN") 12 else 8
                                    val retryDelay = if (isp.uppercase() == "WATEEN") 1500L else 1000L
                                    if (readRetryCount < maxRetries) {
                                        readRetryCount++
                                        Log.d(TAG, "Balance not found, retry $readRetryCount for $isp")
                                        handler.postDelayed({
                                            if (!isFinished) onPageFinished(webView, webView.url)
                                        }, retryDelay)
                                    } else {
                                        if (isp.uppercase() == "WATEEN" && !url.contains("accounting/mybalance")) {
                                            webView.loadUrl("https://panel.wateen.com/")
                                        } else if (isp.uppercase() == "ZONG" && url.contains("login.php")) {
                                            webView.loadUrl("https://turbonet.zong.com.pk/customers.php")
                                        } else if (isp.uppercase() == "EBONE" && url.contains("logincheck")) {
                                            webView.loadUrl("https://partner.ebill.pk/clients")
                                        } else {
                                            Log.e(TAG, "Exhausted retries for $isp balance check")
                                            if (!isFinished) {
                                                isFinished = true
                                                handler.removeCallbacks(timeoutRunnable)
                                                try { webView.destroy() } catch (_: Exception) {}
                                                onComplete(null)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            webView.loadUrl(initialUrl)
        }
    }

    private fun readBalance(isp: String, zone: String, webView: WebView, callback: (Double?) -> Unit) {
        val script = when (isp.uppercase()) {
            "EBONE" -> """
                (function(){
                    var footer = document.querySelector('.dropdown-menu li.footer');
                    var text = footer ? (footer.innerText || footer.textContent || '') : '';
                    if (text && text.indexOf('Balance') > -1) {
                        var m = text.match(/Balance:\s*(-?[0-9.,]+)/i);
                        if (m) return m[1];
                    }
                    var bodyText = document.body ? (document.body.innerText || '') : '';
                    var bm = bodyText.match(/Balance:\s*(-?[0-9.,]+)/i);
                    if (bm) return bm[1];

                    var icon = document.querySelector('i.fa-dollar') || document.querySelector('.fa-dollar');
                    var link = icon ? icon.closest('a') : null;
                    if (link) {
                        link.click();
                        return 'waiting';
                    }
                    return null;
                })()
            """.trimIndent()
            "WATEEN" -> """
                (function(){
                    var body = document.body.innerText || '';
                    var m = body.match(/My\s+Balance[\s\S]{0,150}?([0-9][0-9,]*(?:\.[0-9]{1,2})?)/i);
                    if(m) return m[1];
                    
                    var valEl = document.querySelector('a[href*="accounting/mybalance"] span.h2') || 
                               document.querySelector('.card-stats span.h2') ||
                               document.querySelector('.card span.h2');
                    if(valEl) {
                        var v = (valEl.innerText || valEl.textContent || '').trim();
                        if(v && /[0-9]/.test(v)) return v;
                    }
                    return null;
                })()
            """.trimIndent()
            "ZONG" -> """
                (function(){
                    var text = document.body.innerText || '';
                    var m = text.match(/Available Credit Rs\.?\s*(-?[0-9,]+(\.[0-9]+)?)/i);
                    if(m) return m[1];
                    var m2 = text.match(/Account Credit[^0-9\-]*(-?[0-9,]+(\.[0-9]+)?)/i);
                    return m2 ? m2[1] : null;
                })()
            """.trimIndent()
            "JAZZ" -> """
                (function(){
                    var text = document.body.innerText || '';
                    var m = text.match(/Balance[^0-9\-]*(-?[0-9,]+(\.[0-9]+)?)/i);
                    return m ? m[1] : null;
                })()
            """.trimIndent()
            else -> ""
        }

        webView.evaluateJavascript(script) { res ->
            val clean = res?.trim()?.removeSurrounding("\"")
            if (clean != null && clean != "null" && clean != "waiting") {
                val balance = clean.replace(",", "").toDoubleOrNull()
                callback(balance)
            } else {
                callback(null)
            }
        }
    }

    private fun getIspSessionCookie(context: Context, isp: String, zone: String): String {
        return try {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(context, ISP_SESSION_PREFS, masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            prefs.getString("${isp}_$zone", "") ?: ""
        } catch (e: Exception) { "" }
    }

    private fun saveIspSessionCookie(context: Context, isp: String, zone: String, cookie: String) {
        try {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(context, ISP_SESSION_PREFS, masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            prefs.edit().putString("${isp}_$zone", cookie).apply()
        } catch (e: Exception) { }
    }
}
