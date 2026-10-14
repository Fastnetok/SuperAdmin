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
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class JazzWebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "JazzWebView"
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

        findViewById<Button>(R.id.btnAutoLogin).setOnClickListener {
            if (!isPageLoaded) {
                Toast.makeText(this, "Please load the page first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            parseAndExecuteAutoLogin()
        }

        configureWebView()
        updateStatus("Loading Jazz Franchise Panel...")
        webView.loadUrl(jazzUrl.trimEnd('/') + "/users/login")
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
}
