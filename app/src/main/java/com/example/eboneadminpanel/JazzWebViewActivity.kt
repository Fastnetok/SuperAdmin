package com.example.superadmin

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.SharedPreferences
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

class JazzWebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "JazzWebView"
        private const val KEY_ACCOUNTS = "accounts_json"
        private const val KEY_ACTIVE = "active_account"
    }

    private lateinit var webView: WebView
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var tvCaptchaInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private var targetZone = "Okara"
    private var isPageLoaded = false
    private var loginAttemptInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jazz_webview)

        targetZone = intent.getStringExtra("target_zone")?.ifBlank { null } ?: "Okara"

        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        tvCaptchaInfo = findViewById(R.id.tvCaptchaInfo)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        webView = findViewById(R.id.loginWebView)

        // Pre-fill saved credentials (support custom url if configured in IspPanelSettingsActivity)
        val savedUser = IspPanelSettingsActivity.getSavedUsername(this, "JAZZ", targetZone) ?: ""
        val savedPass = IspPanelSettingsActivity.getSavedPassword(this, "JAZZ", targetZone) ?: ""
        if (savedUser.isNotEmpty()) etUsername.setText(savedUser)
        if (savedPass.isNotEmpty()) etPassword.setText(savedPass)

        val jazzUrl = IspPanelSettingsActivity.getSavedUrl(this, "JAZZ", targetZone)?.takeIf { it.isNotBlank() } ?: "https://biz.jazzftth.pk/"

        findViewById<Button>(R.id.btnLoadUrl).setOnClickListener {
            updateStatus("Connecting to Jazz Franchise Panel...")
            webView.loadUrl(jazzUrl)
        }

        findViewById<Button>(R.id.accountSwitchButton)?.setOnClickListener {
            showAccountListDialog()
        }

        findViewById<Button>(R.id.btnAutoLogin).setOnClickListener {
            if (!isPageLoaded) {
                Toast.makeText(this, "Please load the page first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            parseAndExecuteAutoLogin()
        }

        configureWebView()

        val savedCookie = getIspSessionCookie("JAZZ", targetZone)
        if (savedCookie.isNotEmpty()) {
            savedCookie.split(";").forEach { part ->
                if (part.isNotBlank()) CookieManager.getInstance().setCookie("https://biz.jazzftth.pk", part.trim())
            }
            CookieManager.getInstance().flush()
            updateStatus("Restoring Jazz session cookie...")
            webView.loadUrl("https://biz.jazzftth.pk/dashboard")
        } else {
            updateStatus("Loading Jazz Franchise Panel...")
            webView.loadUrl(jazzUrl.trimEnd('/') + "/users/login")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                updateStatus("Loading page: $url ...")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                isPageLoaded = true
                updateStatus("Page loaded successfully: $url")

                if (url == null) return
                CookieManager.getInstance().flush()

                val currentCookie = CookieManager.getInstance().getCookie("https://biz.jazzftth.pk")
                if (!currentCookie.isNullOrEmpty()) {
                    saveIspSessionCookie("JAZZ", targetZone, currentCookie)
                }

                if (url.contains("login") || url.contains("users/login")) {
                    if (!loginAttemptInProgress) {
                        loginAttemptInProgress = true
                        webView.postDelayed({ parseAndExecuteAutoLogin() }, 2000)
                    }
                } else if (url.contains("jazzftth.pk") || url.contains("biz.jazzftth.pk")) {
                    if (!url.contains("login") && !url.contains("users/login")) {
                        webView.postDelayed({ readJazzBalance() }, 1000)
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                progressBar.visibility = View.GONE
                updateStatus("Error loading page: ${error?.description}")
            }
        }
    }

    private fun updateStatus(status: String) {
        tvStatus.text = "Status: $status"
    }

    private fun parseAndExecuteAutoLogin() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter username and password", Toast.LENGTH_SHORT).show()
            return
        }

        updateStatus("Parsing captcha & attempting auto-login...")

        fun attemptParse(retriesLeft: Int) {
            val jsQuery = "(function() { " +
                    "var f = document.getElementById('first'); " +
                    "var s = document.getElementById('second'); " +
                    "return (f ? f.innerText.trim() : '') + ',' + (s ? s.innerText.trim() : ''); " +
                    "})();"

            webView.evaluateJavascript(jsQuery) { result ->
                try {
                    val cleaned = result?.replace("\"", "") ?: ""
                    val parts = cleaned.split(",")
                    if (parts.size == 2 && parts[0].toIntOrNull() != null && parts[1].toIntOrNull() != null) {
                        val firstNum = parts[0].toInt()
                        val secondNum = parts[1].toInt()
                        val sum = firstNum + secondNum
                        tvCaptchaInfo.text = "Captcha: $firstNum + $secondNum = $sum"

                        executeAutoFillAndSubmit(username, password, sum)
                    } else if (retriesLeft > 0) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            attemptParse(retriesLeft - 1)
                        }, 600)
                    } else {
                        Toast.makeText(this, "Could not detect captcha. Please ensure page is fully loaded.", Toast.LENGTH_LONG).show()
                        updateStatus("Captcha detection failed. Please retry.")
                    }
                } catch (e: Exception) {
                    if (retriesLeft > 0) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            attemptParse(retriesLeft - 1)
                        }, 600)
                    } else {
                        Toast.makeText(this, "Parse error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        attemptParse(5)
    }

    private fun executeAutoFillAndSubmit(username: String, password: String, sum: Int) {
        val autoScript = """
            (function() {
                var userField = document.getElementById('user_login');
                var passField = document.getElementById('user_pass');
                var ansField = document.getElementById('user');
                var sumField = document.getElementById('sum');

                if (userField) {
                    userField.value = '$username';
                    userField.dispatchEvent(new Event('input'));
                }
                if (passField) {
                    passField.value = '$password';
                    passField.dispatchEvent(new Event('input'));
                }
                if (ansField) {
                    ansField.value = '$sum';
                    ansField.dispatchEvent(new Event('input'));
                    ansField.dispatchEvent(new Event('keyup'));
                }
                if (sumField) {
                    sumField.value = '$sum';
                }

                if (typeof check === 'function') {
                    check();
                }

                var btn = document.getElementById('login_btn');
                if (btn) {
                    btn.disabled = false;
                    btn.removeAttribute('disabled');
                    if (typeof handleSubmit === 'function') {
                        handleSubmit(new Event('click'));
                    } else {
                        document.getElementById('loginform').submit();
                    }
                    return 'SUCCESS_SUBMITTED';
                }
                return 'BUTTON_NOT_FOUND';
            })();
        """.trimIndent()

        webView.evaluateJavascript(autoScript) { res ->
            updateStatus("Auto-filled & submitted! Result: $res")
            Toast.makeText(this, "Auto-filled & Submitted!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readJazzBalance(attempt: Int = 1) {
        val js = "(function(){" +
                "  var eye = document.querySelector('i.fa.fa-eye');" +
                "  if(eye) { eye.click(); }" +
                "  var wallet = document.getElementById('walletAmount');" +
                "  if(wallet){" +
                "    var val = wallet.getAttribute('data-amount') || wallet.innerText || '';" +
                "    return val.replace(/,/g, '').trim();" +
                "  }" +
                "  return '';" +
                "})()"

        webView.evaluateJavascript(js) { res ->
            val clean = res?.removeSurrounding("\"")?.replace(",", "")?.toDoubleOrNull()
            if (clean != null && clean != 0.0) {
                Log.d(TAG, "Jazz balance read successfully: $clean")
                FranchiseBalanceManager.updateBalance(this, "JAZZ", clean, targetZone) {
                    FranchiseBalanceManager.showUpdateNotification(this, "JAZZ", clean, targetZone)
                    FranchiseBalanceManager.checkAndNotifyLowBalance(this, "JAZZ", clean, targetZone)
                    setResult(RESULT_OK, Intent().apply {
                        putExtra("checked_balance", clean)
                        putExtra("selected_isp", "JAZZ")
                        putExtra("target_zone", targetZone)
                    })
                    finish()
                }
            } else if (attempt < 10) {
                webView.postDelayed({ readJazzBalance(attempt + 1) }, 1200)
            } else {
                Log.e(TAG, "Could not read Jazz balance after $attempt attempts")
                Toast.makeText(this, "Could not read Jazz balance", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getIspSessionCookie(isp: String, zone: String): String {
        return try {
            val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(this, "isp_session_cookies", masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            prefs.getString("${isp}_$zone", "") ?: ""
        } catch (e: Exception) { "" }
    }

    private fun saveIspSessionCookie(isp: String, zone: String, cookie: String) {
        try {
            val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(this, "isp_session_cookies", masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            prefs.edit().putString("${isp}_$zone", cookie).apply()
        } catch (e: Exception) { }
    }

    private var activeAccountName: String = ""

    private fun getJazzPrefsName(): String {
        val cleanZone = targetZone.trim().lowercase().replace("-", "_")
        return "jazz_accounts_$cleanZone"
    }

    private fun securePrefs(name: String): SharedPreferences {
        val mk = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return try {
            EncryptedSharedPreferences.create(this, name, mk, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (e: Exception) {
            getSharedPreferences(name, MODE_PRIVATE)
        }
    }

    private fun showAccountListDialog() {
        val prefsName = getJazzPrefsName()
        val raw = securePrefs(prefsName).getString(KEY_ACCOUNTS, "") ?: ""
        val accounts = if (raw.isEmpty()) JSONObject() else JSONObject(raw)
        val names = accounts.keys().asSequence().toList()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_account_list, null)
        val listView = dialogView.findViewById<ListView>(R.id.accountListView)
        val addButton = dialogView.findViewById<Button>(R.id.addAccountButton)
        val dialog = AlertDialog.Builder(this).setTitle("Jazz Accounts ($targetZone)").setView(dialogView).setNegativeButton("Close", null).create()
        val adapter = object : ArrayAdapter<String>(this, R.layout.item_account_row, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_account_row, parent, false)
                val name = names[position]
                view.findViewById<TextView>(R.id.accountNameText).text = name
                view.findViewById<TextView>(R.id.accountStatusText).text = if (name == activeAccountName) "Active" else ""
                view.setOnClickListener { switchToAccount(name); dialog.dismiss() }
                view.findViewById<Button>(R.id.deleteAccountButton).setOnClickListener {
                    val updated = if (raw.isEmpty()) JSONObject() else JSONObject(raw)
                    updated.remove(name)
                    securePrefs(prefsName).edit().putString(KEY_ACCOUNTS, updated.toString()).apply()
                    dialog.dismiss()
                    showAccountListDialog()
                }
                return view
            }
        }
        listView.adapter = adapter
        addButton.setOnClickListener { dialog.dismiss(); showAddAccountDialog() }
        dialog.show()
    }

    private fun showAddAccountDialog() {
        val prefsName = getJazzPrefsName()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        val ni = EditText(this).apply { hint = "Account name (e.g. Akmal)" }
        val ui = EditText(this).apply { hint = "Jazz username" }
        val pi = EditText(this).apply { hint = "Jazz password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(ni); layout.addView(ui); layout.addView(pi)
        AlertDialog.Builder(this).setTitle("Add new account").setView(layout).setPositiveButton("Save and login") { _, _ ->
            val an = ni.text.toString().trim()
            val u = ui.text.toString().trim()
            val p = pi.text.toString().trim()
            if (an.isEmpty() || u.isEmpty() || p.isEmpty()) return@setPositiveButton
            val raw = securePrefs(prefsName).getString(KEY_ACCOUNTS, "") ?: ""
            val accounts = if (raw.isEmpty()) JSONObject() else JSONObject(raw)
            accounts.put(an, JSONObject().apply { put("username", u); put("password", p); put("cookie", "") })
            securePrefs(prefsName).edit().putString(KEY_ACCOUNTS, accounts.toString()).apply()
            activeAccountName = an
            securePrefs(prefsName).edit().putString(KEY_ACTIVE, an).apply()
            etUsername.setText(u)
            etPassword.setText(p)
            parseAndExecuteAutoLogin()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun switchToAccount(name: String) {
        val prefsName = getJazzPrefsName()
        val raw = securePrefs(prefsName).getString(KEY_ACCOUNTS, "") ?: ""
        val accounts = if (raw.isEmpty()) JSONObject() else JSONObject(raw)
        if (!accounts.has(name)) return
        val acc = accounts.getJSONObject(name)
        val u = acc.optString("username", "")
        val p = acc.optString("password", "")
        activeAccountName = name
        securePrefs(prefsName).edit().putString(KEY_ACTIVE, name).apply()
        if (u.isNotEmpty() && p.isNotEmpty()) {
            etUsername.setText(u)
            etPassword.setText(p)
            parseAndExecuteAutoLogin()
        }
    }
}
