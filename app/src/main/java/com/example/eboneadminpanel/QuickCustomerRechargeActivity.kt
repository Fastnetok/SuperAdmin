package com.example.superadmin

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.superadmin.core.CompanyDataHelper
import com.example.superadmin.core.SessionManager
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

/**
 * Dedicated, Zero-Delay "Quick Customer Package Recharge & Activation" screen.
 * Displays only the ISP panels (EBONE, WATEEN, ZONG, JAZZ) that are enabled
 * for the currently active company (e.g. DEP-001, OKA-001) per SuperAdmin &
 * CityFranchiseManagementActivity settings.
 *
 * Tapping any enabled ISP panel card launches its automated WebView,
 * pre-navigating directly to the customer search / clients list page.
 */
class QuickCustomerRechargeActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val enabledIsps = mutableListOf<String>()

    private lateinit var searchInput: AutoCompleteTextView
    private lateinit var panelsContainer: LinearLayout
    private lateinit var tvCompanySub: TextView
    private lateinit var tvStatus: TextView

    private var customerDocs = mutableListOf<DocumentSnapshot>()
    private var activeComp = "DEP-001"

    private val bgLight = Color.parseColor("#F4F6FA")
    private val textDark = Color.parseColor("#172033")
    private val textMuted = Color.parseColor("#667085")
    private val borderLight = Color.parseColor("#E4E7EC")

    private val ispColors = mapOf(
        "EBONE" to Color.parseColor("#F97316"),
        "WATEEN" to Color.parseColor("#1E3A8A"),
        "ZONG" to Color.parseColor("#7E22CE"),
        "JAZZ" to Color.parseColor("#E11D48")
    )

    private val ispDisplayNames = mapOf(
        "EBONE" to "Ebone Partner Panel",
        "WATEEN" to "Wateen Smart Panel",
        "ZONG" to "Zong Turbonet Panel",
        "JAZZ" to "Jazz FTTH Biz Panel"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            window.statusBarColor = Color.WHITE
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            window.statusBarColor = Color.parseColor("#E0E0E0")
        }

        val sessionComp = SessionManager(this).getActiveCompanyId()
        activeComp = sessionComp ?: CompanyDataHelper.getCurrentCompanyId(this) ?: "DEP-001"
        val cleanZone = CompanyDataHelper.getCanonicalDocId(activeComp)

        setContentView(buildScreen(cleanZone))
        loadEnabledIspsAndRender()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildScreen(cleanZone: String): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgLight)
            fitsSystemWindows = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        scroll.addView(root)

        // Header Row
        val headerRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 28f
            setPadding(0, 0, dp(14), 0)
            setTextColor(textDark)
            setOnClickListener { finish() }
        })

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@QuickCustomerRechargeActivity).apply {
                text = "Package Activation & Recharge"
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textDark)
            })
            tvCompanySub = TextView(this@QuickCustomerRechargeActivity).apply {
                text = "Active Zone: $cleanZone ($activeComp)"
                textSize = 12f
                setTextColor(textMuted)
            }
            addView(tvCompanySub)
        }
        headerRow.addView(titleBox)
        root.addView(headerRow)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(16))
        })

        // ISP Panels Section Title
        root.addView(TextView(this).apply {
            text = "Select ISP Panel to Recharge"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textDark)
            setPadding(dp(2), 0, 0, dp(10))
        })

        panelsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(panelsContainer)

        tvStatus = TextView(this).apply {
            text = "Loading ISP Panels for $cleanZone..."
            textSize = 12f
            setTextColor(textMuted)
            setPadding(dp(2), dp(12), 0, 0)
        }
        root.addView(tvStatus)

        return scroll
    }

    private fun loadEnabledIspsAndRender() {
        val cleanComp = CompanyDataHelper.getCanonicalDocId(activeComp)
        CityPermissionManager.getEnabledCompanies(this, activeComp) { enabled ->
            enabledIsps.clear()
            enabledIsps.addAll(enabled)

            if (enabledIsps.isEmpty()) {
                enabledIsps.addAll(listOf("EBONE", "WATEEN", "ZONG", "JAZZ"))
            }

            renderIspPanelCards(cleanComp)
        }
    }

    private fun renderIspPanelCards(cleanComp: String) {
        panelsContainer.removeAllViews()
        tvStatus.text = "Tap any panel below to open automated recharge portal"

        enabledIsps.forEach { isp ->
            val ispKey = isp.uppercase()
            val color = ispColors[ispKey] ?: Color.parseColor("#2563EB")
            val title = ispDisplayNames[ispKey] ?: "$ispKey Franchise Panel"

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(2), color)
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }

                val iconBox = TextView(this@QuickCustomerRechargeActivity).apply {
                    text = when (ispKey) {
                        "EBONE" -> "⚡"
                        "WATEEN" -> "📡"
                        "ZONG" -> "🟣"
                        "JAZZ" -> "🚀"
                        else -> "🌐"
                    }
                    textSize = 22f
                    setPadding(0, 0, dp(14), 0)
                }
                addView(iconBox)

                val textBox = LinearLayout(this@QuickCustomerRechargeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    addView(TextView(this@QuickCustomerRechargeActivity).apply {
                        text = title
                        textSize = 15f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(color)
                    })
                    addView(TextView(this@QuickCustomerRechargeActivity).apply {
                        text = "Auto-navigate to $ispKey ($cleanComp) search & recharge"
                        textSize = 11f
                        setTextColor(textMuted)
                    })
                }
                addView(textBox)

                val arrow = TextView(this@QuickCustomerRechargeActivity).apply {
                    text = "▶"
                    textSize = 14f
                    setTextColor(color)
                }
                addView(arrow)

                setOnClickListener {
                    launchIspRechargeWebView(ispKey, cleanComp)
                }
            }

            panelsContainer.addView(card)
        }
    }

    private fun launchIspRechargeWebView(isp: String, cleanComp: String) {
        val targetClass = when (isp.uppercase()) {
            "EBONE" -> CustomerRechargeEboneWebViewActivity::class.java
            "WATEEN" -> CustomerRechargeWateenWebViewActivity::class.java
            "ZONG" -> CustomerRechargeZongWebViewActivity::class.java
            "JAZZ" -> CustomerRechargeJazzWebViewActivity::class.java
            else -> CustomerRechargeEboneWebViewActivity::class.java
        }

        val intent = Intent(this, targetClass).apply {
            putExtra("selected_isp", isp)
            putExtra("target_zone", activeComp)
            putExtra("manual_action", "CUSTOMER_SEARCH")
        }
        startActivity(intent)
    }

    private fun loadCustomersInstantCache() {
        // Step 1: Read INSTANTLY from local disk cache (1 millisecond response!)
        db.collection("customers").get(Source.CACHE).addOnSuccessListener { snapshot ->
            if (snapshot != null && !snapshot.isEmpty) {
                processCustomerSnapshot(snapshot)
            }
            fetchCustomersFromNetwork()
        }.addOnFailureListener {
            fetchCustomersFromNetwork()
        }
    }

    private fun fetchCustomersFromNetwork() {
        db.collection("customers").get(Source.DEFAULT).addOnSuccessListener { snapshot ->
            if (snapshot != null) {
                processCustomerSnapshot(snapshot)
            }
        }
    }

    private fun processCustomerSnapshot(snapshot: com.google.firebase.firestore.QuerySnapshot) {
        customerDocs = snapshot.documents.filter { doc ->
            CompanyDataHelper.belongsToCurrentCompany(this@QuickCustomerRechargeActivity, doc)
        }.toMutableList()

        val rawLabels = customerDocs.map { doc ->
            val id = doc.getString("customerId") ?: doc.id
            val name = doc.getString("name") ?: doc.getString("customerName") ?: ""
            val pkg = doc.getString("packageId") ?: ""
            if (name.isNotBlank() && !name.equals(id, true)) "$id ($name) · $pkg" else "$id · $pkg"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, rawLabels)
        searchInput.setAdapter(adapter)
    }
}
