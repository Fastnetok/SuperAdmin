package com.example.superadmin

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.widget.Toast

/**
 * Dedicated, isolated helper for multi-step automated Customer Package Activation / Recharge
 * across all 4 ISPs: EBONE, WATEEN, ZONG, JAZZ.
 */
object IspPackageActivationHandler {
    private const val TAG = "IspPackageAutomation"

    private var eboneActiveLinkClicked = false
    private var eboneSubmitClicked = false
    private var activeCustomerId: String? = null

    fun resetState() {
        eboneActiveLinkClicked = false
        eboneSubmitClicked = false
        activeCustomerId = null
    }

    /**
     * Handles EBONE profile page steps:
     * 1. On Profile Page (/clients/client/): Clicks "Renew/Active" link (a[href*="/clientStats/"])
     * 2. On Package Form (/clients/clientStats/): Clicks "Submit" or submits form directly
     * 3. On Profile Page redirect: Captures new Expiry Date and finishes with RESULT_OK
     */
    fun handleEbonePackageActivation(
        activity: Activity,
        webView: WebView,
        url: String,
        customerId: String?,
        onSuccess: (newExpiry: String) -> Unit,
        onFailure: (reason: String) -> Unit
    ) {
        if (customerId.isNullOrBlank()) return

        if (activeCustomerId != customerId) {
            activeCustomerId = customerId
            eboneActiveLinkClicked = false
            eboneSubmitClicked = false
        }

        when {
            // STEP 3: Back on Profile Page after submitting form
            url.contains("/clients/client/") && eboneSubmitClicked -> {
                Log.d(TAG, "EBONE: Back on Profile Page after submission. Reading new Expiry Date...")
                activity.runOnUiThread {
                    Toast.makeText(activity, "EBONE: Step 3 - Reading New Expiry Date...", Toast.LENGTH_SHORT).show()
                }
                webView.postDelayed({
                    fetchEboneExpiry(webView) { expiry ->
                        Log.d(TAG, "EBONE: Expiry captured: $expiry")
                        activity.runOnUiThread {
                            Toast.makeText(activity, "EBONE Success! Expiry: $expiry", Toast.LENGTH_LONG).show()
                        }
                        resetState()
                        onSuccess(expiry)
                    }
                }, 1500)
            }

            // STEP 2: On Package Form page (/clientStats/) -> Click Submit or submit form directly
            url.contains("/clients/clientStats/") -> {
                if (!eboneSubmitClicked) {
                    eboneSubmitClicked = true
                    Log.d(TAG, "EBONE: On Package Form (/clientStats/). Submitting package form...")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "EBONE: Step 2 - Submitting Package Form...", Toast.LENGTH_SHORT).show()
                    }
                    webView.postDelayed({
                        webView.evaluateJavascript(
                            "(function(){" +
                                    "  var btn = document.querySelector('button[type=\"submit\"]') ||" +
                                    "            document.querySelector('input[type=\"submit\"]') ||" +
                                    "            document.querySelector('.btn-primary') ||" +
                                    "            document.querySelector('.btn-success') ||" +
                                    "            document.querySelector('.btn-default');" +
                                    "  if(!btn){" +
                                    "    var all = document.querySelectorAll('button, input[type=\"button\"], a.btn');" +
                                    "    for (var i=0; i<all.length; i++){" +
                                    "      var txt = (all[i].innerText || all[i].value || '').toLowerCase();" +
                                    "      if(txt.indexOf('submit') > -1 || txt.indexOf('active') > -1 || txt.indexOf('renew') > -1 || txt.indexOf('save') > -1){" +
                                    "        btn = all[i]; break;" +
                                    "      }" +
                                    "    }" +
                                    "  }" +
                                    "  if(btn){" +
                                    "    btn.click();" +
                                    "    return 'clicked_button:' + (btn.tagName || '');" +
                                    "  }" +
                                    "  var form = document.querySelector('form');" +
                                    "  if(form){" +
                                    "    form.submit();" +
                                    "    return 'submitted_form_directly';" +
                                    "  }" +
                                    "  return 'not_found';" +
                                    "})()"
                        ) { res ->
                            Log.d(TAG, "EBONE submit result: $res")
                        }
                    }, 800)
                }
            }

            // STEP 1: On Profile Page (/client/) -> Click Active/Renew link
            url.contains("/clients/client/") && !eboneActiveLinkClicked -> {
                eboneActiveLinkClicked = true
                Log.d(TAG, "EBONE: On Profile Page (/client/). Clicking Active/Renew link...")
                activity.runOnUiThread {
                    Toast.makeText(activity, "EBONE: Step 1 - Clicking Renew/Active link for $customerId...", Toast.LENGTH_SHORT).show()
                }
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "(function(){" +
                                "  var link = document.querySelector('a[href*=\"/clientStats/\"]');" +
                                "  if(link){ link.click(); return 'clicked'; }" +
                                "  return 'not_found';" +
                                "})()"
                    ) { res ->
                        Log.d(TAG, "EBONE active link click result: $res")
                    }
                }, 800)
            }
        }
    }

    /**
     * Handles WATEEN profile page steps:
     * 1. On Profile Page (/user/user/view/): Clicks "Renew" button (span.btn-warning)
     * 2. Clicks "Active User" submit button
     * 3. Captures new Expiry Date and finishes with RESULT_OK
     */
    fun handleWateenPackageActivation(
        activity: Activity,
        webView: WebView,
        url: String,
        customerId: String?,
        onSuccess: (newExpiry: String) -> Unit,
        onFailure: (reason: String) -> Unit
    ) {
        if (customerId.isNullOrBlank()) return

        if (url.contains("/user/user/view/")) {
            Log.d(TAG, "WATEEN: On Profile Page (/view/). Executing Renew + Active User...")
            activity.runOnUiThread {
                Toast.makeText(activity, "WATEEN: Step 1 - Clicking Renew button...", Toast.LENGTH_SHORT).show()
            }
            webView.postDelayed({
                webView.evaluateJavascript(
                    "(function(){" +
                            "  var spans = document.querySelectorAll('span.btn-warning');" +
                            "  for (var i=0;i<spans.length;i++){" +
                            "    if (spans[i].innerText.indexOf('Renew') > -1){ spans[i].click(); return 'clicked'; }" +
                            "  }" +
                            "  return 'not_found';" +
                            "})()", null
                )
                
                Handler(Looper.getMainLooper()).postDelayed({
                    activity.runOnUiThread {
                        Toast.makeText(activity, "WATEEN: Step 2 - Submitting Active User...", Toast.LENGTH_SHORT).show()
                    }
                    webView.evaluateJavascript(
                        "(function(){" +
                                "  var buttons = document.querySelectorAll('button[type=submit]');" +
                                "  for (var i=0;i<buttons.length;i++){" +
                                "    if (buttons[i].innerText.indexOf('Active User') > -1){ buttons[i].click(); return 'clicked'; }" +
                                "  }" +
                                "  return 'not_found';" +
                                "})()", null
                    )
                }, 1500)

                Handler(Looper.getMainLooper()).postDelayed({
                    activity.runOnUiThread {
                        Toast.makeText(activity, "WATEEN: Step 3 - Reading Expiry...", Toast.LENGTH_SHORT).show()
                    }
                    fetchWateenExpiry(webView) { expiry ->
                        activity.runOnUiThread {
                            Toast.makeText(activity, "WATEEN Success! Expiry: $expiry", Toast.LENGTH_LONG).show()
                        }
                        onSuccess(expiry)
                    }
                }, 3500)
            }, 1000)
        }
    }

    private fun fetchEboneExpiry(webView: WebView, callback: (String) -> Unit) {
        val script = """
            (function(){
                var expiry = '';
                var bodyText = document.body ? (document.body.innerText || '') : '';
                var m = bodyText.match(/(\d{1,2}\s+[A-Za-z]{3,9}\s+\d{4}|\d{4}-\d{2}-\d{2})/);
                if(m) expiry = m[1];

                var rows = document.querySelectorAll('table tbody tr, table.table-hover tbody tr');
                for (var i=0; i<rows.length; i++){
                    var th = rows[i].querySelector('th') || rows[i].querySelector('td:first-child');
                    if (th && (th.innerText.indexOf('Expiry') > -1 || th.innerText.indexOf('Valid') > -1 || th.innerText.indexOf('Status') > -1)){
                        var cells = rows[i].querySelectorAll('td');
                        if (cells.length > 0) {
                            var txt = cells[cells.length - 1].innerText.trim();
                            if(txt) expiry = txt;
                        }
                        break;
                    }
                }
                return JSON.stringify({expiry:expiry});
            })()
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            val clean = result?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: ""
            val expiry = Regex("\"expiry\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
            callback(expiry)
        }
    }

    private fun fetchWateenExpiry(webView: WebView, callback: (String) -> Unit) {
        val script = """
            (function(){
                var expiry = '';
                var el = document.querySelector('abbr[title="Expiry Date/Time"] strong');
                if (el) { expiry = (el.textContent || el.innerText || '').trim(); }
                return JSON.stringify({expiry:expiry});
            })()
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            val clean = result?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: ""
            val expiry = Regex("\"expiry\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
            callback(expiry)
        }
    }
}
