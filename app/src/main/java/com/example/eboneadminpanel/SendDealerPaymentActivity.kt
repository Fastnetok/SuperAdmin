package com.example.superadmin

import android.R
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.superadmin.CityPermissionManager
import com.example.superadmin.core.CompanyDataHelper
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Permanent "Send Dealer Payment" screen — replaces the earlier
 * temporary "Test Dealer Top-Up" dialog. Three fields, in order:
 *   1. Dealer (from the saved "dealers" Firestore collection)
 *   2. Company / panel (Ebone / Wateen / Zong)
 *   3. Amount
 * then a Send button that launches WebViewLoginActivity to do the
 * actual panel automation.
 *
 * Can also be launched PRE-FILLED and LOCKED from a specific
 * SMS-verified dealerTransactions record (the "Send Now" action on a
 * pending payment) via intent extras:
 *   "prefill_dealer_id", "prefill_panel", "prefill_amount",
 *   "prefill_transaction_id"
 * When pre-filled, the dealer/company/amount fields are shown as
 * read-only text instead of editable pickers, so the admin can only
 * confirm-and-send, not silently change what a verified payment pays
 * out.
 *
 * IMPORTANT — current scope:
 *   - Only EBONE is wired to real automation right now (WATEEN/ZONG
 *     selections are accepted here but WebViewLoginActivity does not
 *     yet have a DEALER_TOPUP flow for them — this screen will show a
 *     message rather than silently doing nothing).
 *   - Does NOT check franchise available balance before sending.
 *   - Does NOT have duplicate-submission protection beyond what
 *     WebViewLoginActivity already guards per screen-session.
 */
class SendDealerPaymentActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val companies = mutableListOf("EBONE", "WATEEN", "ZONG")
    private lateinit var companyAdapter: ArrayAdapter<String>

    private var allDealers: List<DocumentSnapshot> = emptyList()
    private var dealers: List<DocumentSnapshot> = emptyList()
    private var selectedDealerIndex = -1
    private var selectedCompanyIndex = 0

    private var isPrefilled = false
    private var prefillDealerId: String? = null
    private var prefillPanel: String? = null
    private var prefillAmount: String? = null
    private var prefillTransactionId: String? = null

    private lateinit var searchDealerInput: EditText
    private lateinit var dealerSpinner: Spinner
    private lateinit var companySpinner: Spinner
    private lateinit var amountInput: EditText
    private lateinit var prefillSummary: TextView
    private lateinit var sendButton: Button
    private lateinit var statusText: TextView

    private val textDark = Color.parseColor("#172033")
    private val textMuted = Color.parseColor("#667085")
    private val borderLight = Color.parseColor("#E4E7EC")
    private val navyMid = Color.parseColor("#1D4ED8")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefillDealerId = intent.getStringExtra("prefill_dealer_id")
        prefillPanel = intent.getStringExtra("prefill_panel")
        prefillAmount = intent.getStringExtra("prefill_amount")
        prefillTransactionId = intent.getStringExtra("prefill_transaction_id")
        isPrefilled = !prefillDealerId.isNullOrBlank()

        setContentView(buildScreen())
        loadDealers()

        val currentZone = CompanyDataHelper.getCurrentCompanyId(this) ?: "OKARA"
        CityPermissionManager.getEnabledCompanies(this, currentZone) { enabledCompanies ->
            companies.clear()
            companies.addAll(enabledCompanies)
            if (::companyAdapter.isInitialized) {
                companyAdapter.notifyDataSetChanged()
            }
            selectedCompanyIndex = 0
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildScreen(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F4F6FA")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }
        scroll.addView(root)

        val headerRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { finish() }
        })
        val activeComp = CompanyDataHelper.getCurrentCompanyId(this) ?: "DEP-001"
        val cleanComp = CompanyDataHelper.getCanonicalDocId(activeComp)

        headerRow.addView(TextView(this).apply {
            text = if (isPrefilled) "Confirm Payment ($cleanComp)" else "Send Payment ($cleanComp)"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textDark)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        
        // Reports button — light grey background with bold black text
        val reportBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E5E7EB"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#D1D5DB"))
            }

            addView(TextView(this@SendDealerPaymentActivity).apply {
                text = "Reports 📊"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textDark)
            })

            setOnClickListener {
                startActivity(Intent(this@SendDealerPaymentActivity, DealerRechargeReportActivity::class.java))
            }
        }
        headerRow.addView(reportBtn)
        
        root.addView(headerRow)

        root.addView(TextView(this).apply {
            text = if (isPrefilled)
                "This payment was already verified from an SMS match. Confirm to send it on the Ebone panel."
            else
                "Pick a dealer, choose which panel's balance to top up, and enter the amount."
            textSize = 12f
            setTextColor(textMuted)
            setPadding(0, dp(6), 0, dp(20))
        })

        prefillSummary = TextView(this).apply {
            textSize = 15f
            setTextColor(textDark)
            visibility = View.GONE
            setPadding(dp(4), dp(4), dp(4), dp(16))
        }
        root.addView(prefillSummary)

        // NEW: Search Dealer Input with Magnifying Glass Icon
        root.addView(fieldLabel("Search Dealer"))
        searchDealerInput = EditText(this).apply {
            hint = "🔍 Search dealer by name, code or mobile..."
            textSize = 13f
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), borderLight)
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterDealersList(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        root.addView(searchDealerInput)
        root.addView(spacer(12))

        root.addView(fieldLabel("Dealer"))
        dealerSpinner = Spinner(this)
        root.addView(dealerSpinner)
        root.addView(spacer(16))

        root.addView(fieldLabel("Company / Panel"))
        companyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, companies)
        companySpinner = Spinner(this).apply {
            adapter = companyAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedCompanyIndex = pos
                    if (!isPrefilled) {
                        updateDealerDropdownForSelectedCompany()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(companySpinner)
        root.addView(spacer(16))

        root.addView(fieldLabel("Amount"))
        amountInput = EditText(this).apply {
            hint = "e.g. 3000"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), borderLight)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(amountInput)
        root.addView(spacer(24))

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(textMuted)
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(statusText)

        sendButton = Button(this).apply {
            text = if (isPrefilled) "Confirm & Send" else "Send Payment"
            setBackgroundColor(navyMid)
            setTextColor(Color.WHITE)
            setOnClickListener { onSendClicked() }
        }
        root.addView(sendButton)

        if (isPrefilled) {
            applyPrefillUi()
        }

        return scroll
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(textMuted)
        setPadding(dp(2), 0, 0, dp(6))
    }

    private fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(heightDp))
    }

    private fun applyPrefillUi() {
        dealerSpinner.isEnabled = false
        companySpinner.isEnabled = false
        amountInput.isEnabled = false
        amountInput.setText(prefillAmount ?: "")
        val panelIndex = companies.indexOf((prefillPanel ?: "EBONE").uppercase())
        if (panelIndex >= 0) companySpinner.setSelection(panelIndex)
    }

    private fun updateDealerDropdownForSelectedCompany() {
        val currentQuery = if (::searchDealerInput.isInitialized) searchDealerInput.text.toString() else ""
        filterDealersList(currentQuery)
    }

    private fun filterDealersList(query: String) {
        val selectedPanel = companies.getOrNull(selectedCompanyIndex)?.uppercase() ?: "EBONE"
        val fieldName = when (selectedPanel) {
            "EBONE" -> "eboneDealerId"
            "WATEEN" -> "wateenDealerId"
            "ZONG" -> "zongDealerId"
            "JAZZ" -> "jazzDealerId"
            else -> "eboneDealerId"
        }

        val q = query.trim().lowercase()
        val filtered = allDealers.filter { doc ->
            val ispId = doc.getString(fieldName)?.trim().orEmpty()
            if (ispId.isBlank()) return@filter false
            if (q.isBlank()) return@filter true

            val name = (doc.getString("name") ?: doc.id).lowercase()
            val code = (doc.getString("dealerCode") ?: doc.getString("code") ?: "").lowercase()
            val phone = (doc.getString("mobile") ?: doc.getString("phone") ?: "").lowercase()
            val zone = (doc.getString("zone") ?: "").lowercase()

            name.contains(q) || code.contains(q) || phone.contains(q) || zone.contains(q) || ispId.lowercase().contains(q)
        }

        dealers = filtered

        if (filtered.isEmpty()) {
            selectedDealerIndex = -1
            dealerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("No matching dealers found"))
        } else {
            val names = filtered.map { doc ->
                val name = doc.getString("name") ?: doc.id
                val zone = doc.getString("zone")?.ifBlank { null } ?: "Okara"
                "$name ($zone)"
            }
            dealerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            dealerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedDealerIndex = pos
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            selectedDealerIndex = 0
            dealerSpinner.setSelection(0)
        }
    }

    private fun loadDealers() {
        // Step 1: Load INSTANTLY from local disk cache (1 millisecond response!)
        db.collection("dealers").orderBy("name").get(com.google.firebase.firestore.Source.CACHE)
            .addOnSuccessListener { snapshot ->
                if (snapshot != null && !snapshot.isEmpty) {
                    processLoadedDealersSnapshot(snapshot)
                }
                fetchLatestDealersFromNetwork()
            }
            .addOnFailureListener {
                fetchLatestDealersFromNetwork()
            }
    }

    private fun fetchLatestDealersFromNetwork() {
        db.collection("dealers").orderBy("name").get(com.google.firebase.firestore.Source.DEFAULT)
            .addOnSuccessListener { snapshot ->
                if (snapshot != null) {
                    processLoadedDealersSnapshot(snapshot)
                }
            }
            .addOnFailureListener { e ->
                if (allDealers.isEmpty()) {
                    statusText.text = "Could not load dealers: ${e.message}"
                }
            }
    }

    private fun processLoadedDealersSnapshot(snapshot: com.google.firebase.firestore.QuerySnapshot) {
        allDealers = snapshot.documents.filter { doc ->
            CompanyDataHelper.belongsToCurrentCompany(this@SendDealerPaymentActivity, doc)
        }
        updateDealerDropdownForSelectedCompany()

        if (isPrefilled) {
            val idx = dealers.indexOfFirst { it.id == prefillDealerId }
            if (idx >= 0) {
                dealerSpinner.setSelection(idx)
                selectedDealerIndex = idx
                val doc = dealers[idx]
                val zone = doc.getString("zone")?.ifBlank { null } ?: "Okara"
                prefillSummary.visibility = View.VISIBLE
                prefillSummary.text = "${doc.getString("name") ?: "Dealer"} ($zone)  •  ${(prefillPanel ?: "").uppercase()}  •  Rs. ${prefillAmount ?: "?"}"
            }
        }
    }

    private fun onSendClicked() {
        if (selectedDealerIndex < 0 || selectedDealerIndex >= dealers.size) {
            statusText.text = "Please select a dealer."
            return
        }
        val amount = amountInput.text.toString().trim()
        if (amount.isEmpty() || amount.toDoubleOrNull() == null || amount.toDouble() <= 0.0) {
            statusText.text = "Please enter a valid amount."
            return
        }
        val panel = companies.getOrNull(selectedCompanyIndex) ?: companies.firstOrNull() ?: "WATEEN"
        val dealerDoc = dealers[selectedDealerIndex]
        val dealerName = dealerDoc.getString("name") ?: dealerDoc.id
        // NEW: the dealer's own zone tag — determines which franchise
        // login (e.g. Renala's own Zong account) gets used, so Renala's
        // payments never go through Okara's panel or vice versa.
        val dealerZone = dealerDoc.getString("zone")?.ifBlank { null } ?: "Okara"

        val ispDealerIdField = when (panel) {
            "WATEEN" -> "wateenDealerId"
            "ZONG" -> "zongDealerId"
            else -> "eboneDealerId"
        }
        val ispDealerId = dealerDoc.getString(ispDealerIdField)?.trim().orEmpty()

        if (ispDealerId.isEmpty()) {
            statusText.text = "This dealer has no $panel Panel Dealer ID saved. Edit the dealer first (Dealer Panel → tap dealer)."
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm payment")
            .setMessage("Send Rs. $amount to $dealerName ($dealerZone zone) via $panel Dealer ID: $ispDealerId?")
            .setPositiveButton("Send") { _, _ ->
                sendButton.isEnabled = false
                statusText.text = "Opening $panel ($dealerZone) panel…"
                
                val targetActivity = WebViewRouter.getTargetActivity(panel, dealerZone)
                val webIntent = Intent(this, targetActivity).apply {
                    putExtra("selected_isp", panel)
                    putExtra("manual_action", "DEALER_TOPUP")
                    putExtra("dealer_ebone_id", ispDealerId)
                    putExtra("topup_amount", amount)
                    putExtra("dealer_internal_id", dealerDoc.id)
                    putExtra("dealer_display_name", dealerName)
                    putExtra("target_zone", dealerZone)
                    prefillTransactionId?.let { putExtra("source_transaction_id", it) }
                }
                startActivity(webIntent)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
