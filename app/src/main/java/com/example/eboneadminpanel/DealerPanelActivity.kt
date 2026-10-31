package com.example.superadmin

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.database.FirebaseDatabase
import androidx.core.util.Pair as AndroidPair
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.example.superadmin.core.CompanyDataHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class DealerPanelActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private var dealersListener: ListenerRegistration? = null
    private var pendingListener: ListenerRegistration? = null

    private lateinit var statDealersText: TextView
    private lateinit var statPendingText: TextView
    private lateinit var statBalanceText: TextView
    private lateinit var pendingList: LinearLayout
    private lateinit var dealerList: LinearLayout

    private var dealerCount = 0
    private var totalBalance = 0.0
    private var currentPendingCount = 0
    private var activeStatFilter: String? = null 
    private val dealersWithPending = HashSet<String>() // NEW: IDs of dealers with pending txn

    private val dealerNameCache = HashMap<String, String>()
    private val inspectorAuthManager = InspectorAuthManager()

    private var pendingFetchIdInput: EditText? = null
    private val REQUEST_FETCH_DEALER_ID = 7001

    private var franchiseBalancesListener: ListenerRegistration? = null
    private lateinit var franchiseBalancesRow: LinearLayout

    private val REQUEST_CHECK_BALANCE = 7002
    private val autoUpdateQueue = mutableListOf<Pair<String, String>>()
    private var isAutoUpdating = false
    private var isBackgroundMode = false
    private var dealerSearchQuery = ""

    private val paymentAccountNames = listOf(
        "EasyPaisa", "JazzCash", "SadaPay", "Raast ID",
        "Till ID", "Faisal Bank", "Alfalah Bank", "Other Bank"
    )

    // ===================== PALETTE =====================
    private val bgLight = Color.parseColor("#F4F6FA")
    private val navyDark = Color.parseColor("#0D1B3E")
    private val navyMid = Color.parseColor("#1D4ED8")
    private val cardWhite = Color.WHITE
    private val textDark = Color.parseColor("#172033")
    private val textMuted = Color.parseColor("#667085")
    private val borderLight = Color.parseColor("#E4E7EC")
    private val green = Color.parseColor("#12B76A")
    private val orange = Color.parseColor("#F79009")
    private val purple = Color.parseColor("#7A5AF8")
    private val amberBg = Color.parseColor("#FFF4E5")
    private val amberText = Color.parseColor("#B45309")

    private lateinit var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initial load of background mode preference
        val prefs = getSharedPreferences("dealer_panel_prefs", MODE_PRIVATE)
        isBackgroundMode = prefs.getBoolean("is_background_mode", false)
        
        setContentView(buildScreen())
        observeDealers()
        observePending()
        observeFranchiseBalances()
    }

    override fun onResume() {
        super.onResume()
        activeStatFilter = "DEALERS"
        observeDealers()
        observePending()
        observeFranchiseBalances()
    }

    override fun onStop() {
        inspectorAuthManager.resetSession()
        super.onStop()
    }

    override fun onDestroy() {
        dealersListener?.remove()
        pendingListener?.remove()
        franchiseBalancesListener?.remove()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, radiusDp: Int = 20): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun outlinedPill(bg: Int, strokeColor: Int, radiusDp: Int = 12): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), strokeColor)
        }

    // ===================== SCREEN =====================

    private fun buildScreen(): View {
        val mainScroll = androidx.core.widget.NestedScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bgLight)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }

        root.addView(buildHeader())
        root.addView(buildStatsRow())

        val franchiseBalancesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        franchiseBalancesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        franchiseBalancesScroll.addView(franchiseBalancesRow)
        root.addView(franchiseBalancesScroll)

        // NEW: Search Dealers bar
        val searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        val searchInput = EditText(this).apply {
            hint = "Search dealers by name..."
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = outlinedPill(Color.WHITE, borderLight, 10)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    dealerSearchQuery = s?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
                    observeDealers() // Trigger re-filter
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchContainer.addView(searchInput)
        root.addView(searchContainer)

        root.addView(sectionTitle("Pending Payments"))
        pendingList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(pendingList)

        root.addView(sectionTitle("Dealers"))
        dealerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        root.addView(dealerList)

        mainScroll.addView(root)
        return mainScroll
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(navyDark, navyMid)
            )
            setPadding(dp(20), dp(48), dp(20), dp(20))
        }

        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { finish() }
        })
        titleRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Dealer Panel"
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Balances, payments & verification"
                textSize = 12f
                setTextColor(Color.parseColor("#B8C2E0"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        })
        header.addView(titleRow)

        val iconScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setPadding(0, dp(14), 0, 0)
        }
        val iconRowWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        iconRow.addView(iconButton("⟳") { scanNow() })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("🔍") { inspectEbonePanel() })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("👥") { openSendPaymentScreen() })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("📄") { startActivity(Intent(this, DealerPaymentLogActivity::class.java)) })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("⚙") { showThresholdSettingsDialog() })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("🏷") { showZoneServiceSettingsDialog() })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("+") { showAddDealerDialog() })
        iconRowWrapper.addView(iconRow)
        iconScroll.addView(iconRowWrapper)
        header.addView(iconScroll)

        return header
    }

    private fun spacerH(widthDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    private fun iconButton(symbol: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#2A3B6E"))
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private var statsContainer: LinearLayout? = null
    
    private fun buildStatsRow(): LinearLayout {
        val container = LinearLayout(this).apply {
            id = View.generateViewId()
            statsContainer = this
            orientation = LinearLayout.VERTICAL
        }
        container.addView(buildStatsCards())
        return container
    }

    private fun buildStatsCards(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(4))
        }

        fun statCard(label: String, colorAccent: Int, type: String): Pair<LinearLayout, TextView> {
            val valueText = TextView(this).apply {
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textDark)
            }
            
            val isSelected = activeStatFilter == type
            
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = outlinedPill(
                    if (isSelected) Color.parseColor("#EFF6FF") else cardWhite, 
                    if (isSelected) Color.parseColor("#2563EB") else borderLight, 
                    14
                )
                elevation = if(isSelected) dp(4).toFloat() else dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also {
                    it.marginEnd = dp(8)
                }
                
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                foreground = AppCompatResources.getDrawable(context, outValue.resourceId)
                isClickable = true

                addView(View(this@DealerPanelActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(4)).also { it.bottomMargin = dp(8) }
                    background = pill(colorAccent, 4)
                })
                addView(valueText)
                addView(TextView(this@DealerPanelActivity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(textMuted)
                })
                
                setOnClickListener {
                    activeStatFilter = if (activeStatFilter == type) null else type
                    refreshStatsRow()
                }
            }
            return card to valueText
        }

        val (dealersCard, dealersVal) = statCard("Dealers", navyMid, "DEALERS")
        val (pendingCard, pendingVal) = statCard("Pending", orange, "PENDING")
        val (balanceCard, balanceVal) = statCard("Total Balance", green, "TOTAL")

        statDealersText = dealersVal
        statPendingText = pendingVal
        statBalanceText = balanceVal

        row.addView(dealersCard)
        row.addView(pendingCard)
        balanceCard.layoutParams = (balanceCard.layoutParams as LinearLayout.LayoutParams).also { it.marginEnd = 0 }
        row.addView(balanceCard)
        return row
    }

    private var lastDealerQuery: com.google.firebase.firestore.QuerySnapshot? = null

    private fun refreshStatsRow() {
        statsContainer?.let {
            it.removeAllViews()
            it.addView(buildStatsCards())
        }
        lastDealerQuery?.let { renderDealers(it) } ?: observeDealers()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textDark)
        setPadding(dp(16), dp(20), dp(16), dp(8))
    }

    // ===================== DEALERS (LIVE) =====================

    private fun observeDealers() {
        dealersListener?.remove()
        dealersListener = db.collection("dealers")
            .addSnapshotListener { query, error ->
                if (error != null || query == null) {
                    Toast.makeText(this, "Dealer list load failed: ${error?.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }
                lastDealerQuery = query
                renderDealers(query)
            }

        FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
            .getReference("dealers")
            .addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    for (child in snapshot.children) {
                        if (CompanyDataHelper.belongsToCurrentCompany(this@DealerPanelActivity, child)) {
                            val id = child.key ?: continue
                            val name = child.child("name").getValue(String::class.java) ?: ""
                            if (id.isNotBlank() && name.isNotBlank()) {
                                dealerNameCache[id] = name
                            }
                        }
                    }
                }

                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
    }

    private fun isMatchingCompanyOrZone(context: Context, doc: DocumentSnapshot): Boolean {
        val session = com.example.superadmin.core.SessionManager(context)
        val activeRaw = session.getActiveCompanyId()?.trim()?.takeIf { it.isNotBlank() }
            ?: session.getCompanyId()?.trim()?.takeIf { it.isNotBlank() }
            ?: CompanyDataHelper.getCurrentCompanyId(context)
            ?: "OKA-001"

        if (activeRaw.equals("ALL", ignoreCase = true)) {
            return true
        }

        val cleanActive = activeRaw.trim().uppercase()
        val canonicalActive = CompanyDataHelper.getCanonicalDocId(cleanActive).uppercase()
        val normActive = cleanActive.replace("-", "").replace("_", "").replace(" ", "")
        val prefixActive = normActive.take(3)

        val dComp = (doc.getString("companyId") ?: doc.getString("company") ?: "").trim().uppercase()
        val dZone = doc.getString("zone")?.trim()?.uppercase() ?: ""

        val dCompCanon = CompanyDataHelper.getCanonicalDocId(dComp).uppercase()
        val dZoneCanon = CompanyDataHelper.getCanonicalDocId(dZone).uppercase()

        val normComp = dComp.replace("-", "").replace("_", "").replace(" ", "")
        val normZone = dZone.replace("-", "").replace("_", "").replace(" ", "")

        // 1. Direct match on companyId or zone or normalized companyId
        if (dComp == cleanActive || dZone == cleanActive) return true
        if (normComp.isNotBlank() && normComp == normActive) return true
        if (normZone.isNotBlank() && normZone == normActive) return true

        // 2. Canonical city name match (e.g. RENALA == RENALA, OKARA == OKARA)
        if (canonicalActive.isNotBlank() && (dCompCanon == canonicalActive || dZoneCanon == canonicalActive)) return true

        // 3. Prefix match (e.g. REN == REN, OKA == OKA, VEH == VEH)
        if (prefixActive.length >= 3 && prefixActive.all { it.isLetter() }) {
            if (normComp.startsWith(prefixActive) || normZone.startsWith(prefixActive)) return true
        }

        // 4. Substring / contains match for double zones or code zones (e.g. OKA-001 matches OKARA or OKA)
        if (cleanActive.contains(dZone) || dZone.contains(cleanActive) || cleanActive.contains(dComp) || dComp.contains(cleanActive)) return true
        if (canonicalActive.isNotBlank() && (dComp.contains(canonicalActive) || dZone.contains(canonicalActive))) return true

        return CompanyDataHelper.belongsToCurrentCompany(context, doc)
    }

    private fun renderDealers(query: com.google.firebase.firestore.QuerySnapshot) {
        dealerList.removeAllViews()

        val validDocs = query.documents.filter { doc ->
            isMatchingCompanyOrZone(this, doc)
        }

        val cleanSearch = dealerSearchQuery.trim().lowercase(Locale.getDefault())

        // Group documents by dealer name so same dealer name ALWAYS shows as ONE single consolidated card
        val groupedDealers = validDocs.groupBy { doc ->
            (doc.getString("name") ?: doc.getString("dealerName") ?: doc.id).trim().lowercase(Locale.getDefault())
        }.filter { (cleanName, _) ->
            cleanSearch.isEmpty() || cleanName.contains(cleanSearch)
        }

        dealerCount = groupedDealers.size
        totalBalance = 0.0

        if (groupedDealers.isEmpty()) {
            dealerList.addView(emptyState("No dealers yet — tap + to add one"))
        } else {
            var index = 1
            groupedDealers.forEach { (_, docsInGroup) ->
                val primaryDoc = docsInGroup.first()
                val name = primaryDoc.getString("name") ?: primaryDoc.getString("dealerName") ?: primaryDoc.id
                val primaryId = primaryDoc.id

                var ebone = 0.0
                var wateen = 0.0
                var zong = 0.0
                var jazz = 0.0
                var eboneId = ""
                var wateenId = ""
                var zongId = ""
                var jazzId = ""
                var mobile = ""
                var dealerCode = ""
                var status = "ACTIVE"
                var zone = "Okara"

                docsInGroup.forEach { doc ->
                    ebone += doc.getDouble("eboneBalance") ?: 0.0
                    wateen += doc.getDouble("wateenBalance") ?: 0.0
                    zong += doc.getDouble("zongBalance") ?: 0.0
                    jazz += doc.getDouble("jazzBalance") ?: 0.0

                    if (eboneId.isBlank()) eboneId = doc.getString("eboneDealerId") ?: ""
                    if (wateenId.isBlank()) wateenId = doc.getString("wateenDealerId") ?: ""
                    if (zongId.isBlank()) zongId = doc.getString("zongDealerId") ?: ""
                    if (jazzId.isBlank()) jazzId = doc.getString("jazzDealerId") ?: ""
                    if (mobile.isBlank()) mobile = doc.getString("mobile") ?: ""
                    if (dealerCode.isBlank()) dealerCode = doc.getString("dealerCode") ?: ""
                    if (doc.getString("status") == "ACTIVE") status = "ACTIVE"
                    val docZone = doc.getString("zone")?.takeIf { it.isNotBlank() }
                    if (docZone != null) zone = docZone
                }

                val combined = ebone + wateen + zong + jazz
                totalBalance += combined
                dealerNameCache[primaryId] = name.ifBlank { primaryId }

                dealerList.addView(dealerCardMerged(primaryId, docsInGroup, index++, name, mobile, dealerCode, zone, status, ebone, wateen, zong, jazz, eboneId, wateenId, zongId, jazzId))
            }
        }

        statDealersText.text = dealerCount.toString()
        statPendingText.text = currentPendingCount.toString()
        statBalanceText.text = "Rs. ${"%.0f".format(totalBalance)}"
    }

    private fun dealerCardMerged(
        primaryId: String,
        docsInGroup: List<DocumentSnapshot>,
        index: Int,
        name: String,
        mobile: String,
        dealerCode: String,
        zone: String,
        status: String,
        ebone: Double,
        wateen: Double,
        zong: Double,
        jazz: Double,
        eboneId: String,
        wateenId: String,
        zongId: String,
        jazzId: String
    ): View {
        val isActive = status == "ACTIVE"

        val cardScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) }
        }

        val rowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(-2, -2)
        }

        val screenWidth = resources.displayMetrics.widthPixels - dp(32)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(cardWhite)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), borderLight)
            }
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(screenWidth, -2)
            isClickable = true
            setOnClickListener { showDealerLedger(primaryId, name, zone) }
        }

        // Back Delete Action View
        val deleteActionView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DC2626"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(100), -1).also { it.marginStart = dp(8) }

            addView(TextView(this@DealerPanelActivity).apply {
                text = "🗑️\nDelete"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })
            setOnClickListener {
                docsInGroup.forEach { doc ->
                    showDeleteDealerConfirmDialog(doc.id, name, zone)
                }
            }
        }

        val topRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        
        topRow.addView(TextView(this).apply {
            text = "$index."
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textMuted)
            setPadding(0, 0, dp(8), 0)
        })

        topRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).also { it.marginEnd = dp(10) }
            background = pill(if (isActive) green else Color.parseColor("#D92D20"), 10)
        })
        topRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPanelActivity).apply {
                text = name
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textDark)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Code ${dealerCode}  •  ${mobile}"
                textSize = 12f
                setTextColor(textMuted)
            })
        })
        
        val zoneColor = if (zone.equals("Okara", true) || zone.contains("OKA", true)) navyMid else purple
        topRow.addView(TextView(this).apply {
            text = zone
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(zoneColor)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = outlinedPill(Color.WHITE, zoneColor, 10)
        })
        card.addView(topRow)

        val badgeRow = LinearLayout(this).apply {
            setPadding(0, dp(10), 0, 0)
        }
        var addedAny = false
        if (ebone > 0 || eboneId.isNotBlank()) {
            badgeRow.addView(networkBalanceBadge("Ebone", ebone, orange))
            addedAny = true
        }
        if (wateen > 0 || wateenId.isNotBlank()) {
            if (addedAny) badgeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            badgeRow.addView(networkBalanceBadge("Wateen", wateen, navyMid))
            addedAny = true
        }
        if (zong > 0 || zongId.isNotBlank()) {
            if (addedAny) badgeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            badgeRow.addView(networkBalanceBadge("Zong", zong, purple))
            addedAny = true
        }
        if (jazz > 0 || jazzId.isNotBlank()) {
            if (addedAny) badgeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            badgeRow.addView(networkBalanceBadge("Jazz", jazz, Color.parseColor("#E11D48")))
            addedAny = true
        }
        if (!addedAny) {
            badgeRow.addView(networkBalanceBadge("Ebone", ebone, orange))
            badgeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            badgeRow.addView(networkBalanceBadge("Wateen", wateen, navyMid))
            badgeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            badgeRow.addView(networkBalanceBadge("Zong", zong, purple))
            badgeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            badgeRow.addView(networkBalanceBadge("Jazz", jazz, Color.parseColor("#E11D48")))
            addedAny = true
        }
        if (addedAny) {
            card.addView(badgeRow)
        }

        // Reset Button at bottom of card
        val actionRow = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        actionRow.addView(TextView(this).apply {
            text = "Reset Balance 🔄"
            textSize = 11f
            setTextColor(Color.parseColor("#C62828"))
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = outlinedPill(Color.WHITE, Color.parseColor("#C62828"), 8)
            setOnClickListener { 
                docsInGroup.forEach { doc ->
                    resetDealerBalance(doc.id, name, zone) 
                }
            }
        })
        card.addView(actionRow)

        rowLayout.addView(card)
        rowLayout.addView(deleteActionView)
        cardScrollView.addView(rowLayout)

        return cardScrollView
    }

    private fun showDeleteDealerConfirmDialog(dealerId: String, name: String, zone: String) {
        val input = EditText(this).apply {
            hint = "Enter Security PIN (1234 or 1912)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Delete Dealer: $name")
            .setMessage("Are you sure you want to delete dealer '$name' ($zone)? Enter PIN (1234 or 1912) to confirm:")
            .setView(input)
            .setPositiveButton("Delete") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin == "1234" || pin == "1912") {
                    db.collection("dealers").document(dealerId).delete().addOnSuccessListener {
                        FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
                            .getReference("dealers").child(dealerId).removeValue()
                        Toast.makeText(this, "Dealer '$name' deleted successfully", Toast.LENGTH_SHORT).show()
                        dealerSearchQuery = ""
                        activeStatFilter = "DEALERS"
                        observeDealers()
                    }
                } else {
                    Toast.makeText(this, "Incorrect PIN. Deletion cancelled.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dealerIdBadge(label: String, dealerId: String, accent: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = outlinedPill(Color.parseColor("#F9FAFB"), borderLight, 8)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPanelActivity).apply {
                text = label
                textSize = 10f
                setTextColor(accent)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "ID: $dealerId"
                textSize = 12f
                setTextColor(textDark)
                setTypeface(null, Typeface.BOLD)
            })
        }

    private fun networkBalanceBadge(label: String, amount: Double, accent: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = outlinedPill(Color.parseColor("#F9FAFB"), borderLight, 10)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPanelActivity).apply {
                text = label
                textSize = 10f
                setTextColor(accent)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Rs. ${"%,.0f".format(amount)}"
                textSize = 13f
                setTextColor(textDark)
                setTypeface(null, Typeface.BOLD)
            })
        }

    // ===================== PENDING PAYMENTS (LIVE) =====================

    private fun observePending() {
        pendingListener = db.collection("dealerTransactions")
            .whereIn("status", listOf("PENDING", "VERIFIED", "NEEDS_REVIEW", "REJECTED_DUPLICATE"))
            .addSnapshotListener { query, error ->
                if (error != null || query == null) return@addSnapshotListener

                pendingList.removeAllViews()

                val relevantDocs = query.documents.filter { doc ->
                    val status = doc.getString("status")
                    val transferStatus = doc.getString("transferStatus") ?: ""
                    status == "PENDING" || status == "NEEDS_REVIEW" || status == "REJECTED_DUPLICATE" ||
                            (status == "VERIFIED" && transferStatus != "TRANSFERRED")
                }

                statPendingText.text = relevantDocs.size.toString()
                
                // NEW: Update dealersWithPending set
                dealersWithPending.clear()
                relevantDocs.forEach { doc ->
                    doc.getString("dealerId")?.let { dealersWithPending.add(it) }
                }

                if (relevantDocs.isEmpty()) {
                    pendingList.addView(emptyState("No pending dealer payments"))
                    return@addSnapshotListener
                }

                relevantDocs.forEach { document ->
                    val status = document.getString("status") ?: "PENDING"
                    val transferStatus = document.getString("transferStatus") ?: ""
                    val panel = document.getString("panel") ?: "?"
                    val amount = document.getDouble("amount") ?: 0.0
                    val tid = document.getString("bankTransactionId") ?: ""
                    val dealerId = document.getString("dealerId") ?: ""
                    val dealerName = dealerNameCache[dealerId] ?: "Dealer"

                    when (status) {
                        "PENDING" -> pendingList.addView(
                            swipeToDeleteWrapper(
                                waitingRow(document.id, dealerName, panel, amount, tid)
                            ) {
                                deletePendingPayment(document.id)
                            }
                        )

                        "NEEDS_REVIEW" -> pendingList.addView(
                            swipeToDeleteWrapper(
                                needsReviewRow(
                                    document.id,
                                    dealerName,
                                    panel,
                                    amount,
                                    tid,
                                    document
                                )
                            ) {
                                deletePendingPayment(document.id)
                            }
                        )

                        "REJECTED_DUPLICATE" -> pendingList.addView(
                            swipeToDeleteWrapper(
                                duplicateRejectedRow(
                                    dealerName = dealerName,
                                    panel = panel,
                                    amount = amount,
                                    tid = tid,
                                    message = document.getString("duplicateMessage").orEmpty()
                                )
                            ) {
                                deletePendingPayment(document.id)
                            }
                        )

                        "VERIFIED" -> {
                            when (transferStatus) {
                                "AUTO_SENDING", "AUTO_CLAIMED" -> pendingList.addView(
                                    swipeToDeleteWrapper(
                                        autoSendingRow(
                                            dealerName,
                                            panel,
                                            amount,
                                            tid
                                        )
                                    ) {
                                        deletePendingPayment(document.id)
                                    }
                                )

                                "AUTO_FAILED" -> pendingList.addView(
                                    swipeToDeleteWrapper(
                                        autoFailedRow(
                                            dealerName = dealerName,
                                            panel = panel,
                                            amount = amount,
                                            tid = tid,
                                            error = document.getString("transferError")
                                                .orEmpty()
                                        )
                                    ) {
                                        deletePendingPayment(document.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
    }

    private fun deletePendingPayment(transactionId: String) {
        db.collection("dealerTransactions").document(transactionId).get().addOnSuccessListener { doc ->
            val tid = doc.getString("bankTransactionId") ?: ""
            db.collection("dealerTransactions").document(transactionId).delete()
            if (tid.isNotBlank()) {
                val cleanTid = tid.filter { it.isLetterOrDigit() }.uppercase()
                db.collection("paymentClaims").whereEqualTo("identifier", cleanTid).get()
                    .addOnSuccessListener { claims ->
                        claims.documents.forEach { it.reference.delete() }
                    }
            }
            db.collection("paymentClaims").whereEqualTo("transactionId", transactionId).get()
                .addOnSuccessListener { claims ->
                    claims.documents.forEach { it.reference.delete() }
                }
            Toast.makeText(this, "Payment record deleted", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener {
            db.collection("dealerTransactions").document(transactionId).delete()
        }
    }

    private fun swipeToDeleteWrapper(contentRow: LinearLayout, onDelete: () -> Unit): FrameLayout {
        val revealedPx = dp(72).toFloat()
        val deleteBg = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#D92D20"))
            layoutParams = FrameLayout.LayoutParams(dp(72), FrameLayout.LayoutParams.MATCH_PARENT).also {
                it.gravity = Gravity.END
            }
            isClickable = true
            addView(TextView(this@DealerPanelActivity).apply {
                text = "🗑"
                textSize = 20f
                setTextColor(Color.WHITE)
            })
        }
        contentRow.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val touchSlopPx = dp(10).toFloat()
        val frame = object : FrameLayout(this) {
            private var downX = 0f
            private var downY = 0f
            private var startTranslation = 0f
            private var dragging = false
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        startTranslation = contentRow.translationX
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (!dragging && abs(dx) > touchSlopPx && abs(dx) > abs(dy)) {
                            dragging = true
                        }
                        if (dragging) return true
                    }
                }
                return false
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        if (dragging) {
                            val dx = ev.x - downX
                            contentRow.translationX = (startTranslation + dx).coerceIn(-revealedPx, 0f)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            val shouldReveal = contentRow.translationX < -revealedPx / 2
                            contentRow.animate().translationX(if (shouldReveal) -revealedPx else 0f).setDuration(150).start()
                            dragging = false
                            return true
                        }
                    }
                }
                return false
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        frame.addView(deleteBg)
        frame.addView(contentRow)
        deleteBg.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete permanently?")
                .setMessage("This will permanently remove this payment record. This cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> onDelete() }
                .setNegativeButton("Cancel") { _, _ ->
                    contentRow.animate().translationX(0f).setDuration(150).start()
                }
                .show()
        }
        return frame
    }

    private fun needsReviewRow(
        transactionId: String,
        dealerName: String,
        panel: String,
        amount: Double,
        tid: String,
        document: DocumentSnapshot
    ): LinearLayout {
        val matchedSmsDate = document.getLong("matchedSmsDate")
        val matchedSmsBody = document.getString("matchedSmsBody") ?: ""
        val dateText = matchedSmsDate?.let {
            SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(it))
        } ?: "unknown date"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(Color.parseColor("#F3E8FF"), purple, 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = "$dealerName  •  $panel  —  Rs. ${"%.0f".format(amount)}"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(purple)
        })
        row.addView(TextView(this).apply {
            text = "TID: $tid  •  Matched SMS is from $dateText, not today"
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, dp(2), 0, dp(8))
        })
        if (matchedSmsBody.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = matchedSmsBody
                textSize = 11f
                setTextColor(textDark)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = outlinedPill(Color.WHITE, borderLight, 8)
                setPadding(0, 0, 0, dp(8))
            })
        }
        row.addView(Button(this).apply {
            text = "Review & Verify"
            setBackgroundColor(purple)
            setTextColor(Color.WHITE)
            textSize = 12f
            setOnClickListener { confirmNeedsReview(transactionId, document) }
        })
        return row
    }

    private fun confirmNeedsReview(transactionId: String, document: DocumentSnapshot) {
        AlertDialog.Builder(this)
            .setTitle("Confirm this payment?")
            .setMessage("This will credit the dealer's balance based on the matched SMS shown. Only confirm if you've checked it's genuine.")
            .setPositiveButton("Confirm & Credit") { _, _ ->
                val data = document.data ?: return@setPositiveButton
                DealerPaymentVerifier.verifyAndCredit(this, transactionId, data, "manual_confirm_needs_review") { success ->
                    runOnUiThread {
                        Toast.makeText(this, if (success) "Confirmed and credited" else "Failed to confirm — try again", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun waitingRow(transactionId: String, dealerName: String, panel: String, amount: Double, tid: String): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(amberBg, Color.parseColor("#FEC84B"), 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = "⏳"
            textSize = 16f
            setPadding(0, 0, dp(10), 0)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPanelActivity).apply {
                text = "$dealerName  •  $panel  —  Rs. ${"%.0f".format(amount)}"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(amberText)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "TID: $tid  •  Waiting for SMS match"
                textSize = 11f
                setTextColor(textMuted)
            })
        })
        row.addView(TextView(this).apply {
            text = "🔄"
            textSize = 18f
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setOnClickListener { retryMatchForOnePayment(transactionId) }
        })
        return row
    }

    private fun retryMatchForOnePayment(transactionId: String) {
        Toast.makeText(this, "Checking for this payment's SMS…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            DealerPaymentSmsScanner.scanAllPending(this@DealerPanelActivity, "manual_retry_button")
            db.collection("dealerTransactions").document(transactionId).get()
                .addOnSuccessListener { doc ->
                    val stillPending = doc.getString("status") == "PENDING"
                    runOnUiThread {
                        Toast.makeText(this@DealerPanelActivity, if (stillPending) "Still no matching SMS found for this payment." else "Matched! Processing…", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    private fun autoSendingRow(dealerName: String, panel: String, amount: Double, tid: String): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(Color.parseColor("#E8F8EE"), green, 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = "✓"
            textSize = 18f
            setTextColor(green)
            setPadding(0, 0, dp(10), 0)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPanelActivity).apply {
                text = "$dealerName  •  $panel  —  Rs. ${"%.0f".format(amount)}"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#18794E"))
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "TID: $tid  •  Verified — automatic transfer in progress"
                textSize = 11f
                setTextColor(textMuted)
            })
        })
        return row
    }

    private fun autoFailedRow(dealerName: String, panel: String, amount: Double, tid: String, error: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(Color.parseColor("#FEECEC"), Color.parseColor("#D92D20"), 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = "⚠ $dealerName  •  $panel  —  Rs. ${"%.0f".format(amount)}"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#B42318"))
        })
        row.addView(TextView(this).apply {
            text = if (error.isBlank()) "TID: $tid  •  Automatic transfer failed" else "TID: $tid  •  $error"
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, dp(3), 0, 0)
        })
        return row
    }

    private fun duplicateRejectedRow(dealerName: String, panel: String, amount: Double, tid: String, message: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(Color.parseColor("#FFF7E6"), Color.parseColor("#B54708"), 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = "⛔ $dealerName  •  $panel  —  Rs. ${"%.0f".format(amount)}"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#B54708"))
        })
        row.addView(TextView(this).apply {
            text = if (message.isBlank()) "TID: $tid  •  Rejected — this TID was already used elsewhere" else "TID: $tid  •  $message"
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, dp(3), 0, 0)
        })
        return row
    }

    private fun emptyState(message: String): TextView = TextView(this).apply {
        text = message
        textSize = 13f
        setTextColor(textMuted)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(16), dp(8), dp(16))
    }

    // ===================== ACTIONS =====================

    private fun inspectEbonePanel() {
        val currentComp = CompanyDataHelper.getCurrentCompanyId(this) ?: "Okara"
        inspectorAuthManager.inspect(this, getFranchiseDocRef(), currentComp)
    }

    private fun fetchWateenDealerId(targetInput: EditText, dealerName: String) {
        pendingFetchIdInput = targetInput
        val intent = Intent(this, WebViewLoginActivity::class.java).apply {
            putExtra("selected_isp", "WATEEN")
            putExtra("manual_action", "FETCH_DEALER_ID")
            putExtra("dealer_search_name", dealerName)
        }
        startActivityForResult(intent, REQUEST_FETCH_DEALER_ID)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val currentComp = CompanyDataHelper.getCurrentCompanyId(this) ?: "Okara"
        if (inspectorAuthManager.handleActivityResult(requestCode, this, getFranchiseDocRef(), currentComp)) {
            return
        }
        if (requestCode == REQUEST_FETCH_DEALER_ID && resultCode == RESULT_OK) {
            val fetchedId = data?.getStringExtra("fetched_dealer_id")
            if (!fetchedId.isNullOrBlank()) {
                pendingFetchIdInput?.setText(fetchedId)
                Toast.makeText(this, "Found Wateen ID: $fetchedId", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Could not find that dealer on Wateen", Toast.LENGTH_LONG).show()
            }
            pendingFetchIdInput = null
        } else if (requestCode == REQUEST_CHECK_BALANCE) {
            if (isAutoUpdating) {
                Handler(Looper.getMainLooper()).postDelayed({ processNextAutoUpdate() }, 1200)
            }
        }
    }

    private fun openSendPaymentScreen() {
        startActivity(Intent(this, SendDealerPaymentActivity::class.java))
    }

    private fun checkEboneBalance() {
        val currentComp = CompanyDataHelper.getCurrentCompanyId(this) ?: "OKARA"
        val canonicalZone = CompanyDataHelper.getCanonicalDocId(currentComp)

        val docRef = getFranchiseDocRef()
        docRef.get().addOnSuccessListener { snapshot ->
            val enabledMap = snapshot.get("enabledCompanies") as? Map<*, *>
            val eboneEnabled = (enabledMap?.get("EBONE") as? Boolean) ?: false
            val wateenEnabled = (enabledMap?.get("WATEEN") as? Boolean) ?: false
            val zongEnabled = (enabledMap?.get("ZONG") as? Boolean) ?: false
            val jazzEnabled = (enabledMap?.get("JAZZ") as? Boolean) ?: false

            val availablePanels = mutableListOf<Pair<String, String>>()
            if (eboneEnabled) availablePanels.add("Ebone" to "EBONE")
            if (wateenEnabled) availablePanels.add("Wateen" to "WATEEN")
            if (zongEnabled) availablePanels.add("Zong" to "ZONG")
            if (jazzEnabled) availablePanels.add("Jazz" to "JAZZ")

            if (availablePanels.isEmpty()) {
                Toast.makeText(this, "No ISP panels enabled for this franchise in City & Franchise Management", Toast.LENGTH_LONG).show()
                return@addOnSuccessListener
            }

            val labels = availablePanels.map { it.first }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Check balance for which panel? ($canonicalZone)")
                .setItems(labels) { _, which ->
                    val isp = availablePanels[which].second
                    launchCheckBalance(isp, canonicalZone)
                }
                .show()
        }
    }

    private fun launchCheckBalance(isp: String, zone: String) {
        val targetActivity = WebViewRouter.getTargetActivity(isp, zone)
        val intent = Intent(this, targetActivity).apply {
            putExtra("selected_isp", isp)
            putExtra("manual_action", "CHECK_BALANCE")
            putExtra("target_zone", zone)
        }
        if (isAutoUpdating) {
            startActivityForResult(intent, REQUEST_CHECK_BALANCE)
        } else {
            startActivity(intent)
        }
    }

    private fun showStatusNotification(title: String, message: String) {
        val channelId = "auto_update_status_v4" // Fresh channel to ensure all settings (sound, priority) are applied
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Update Status", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Panel updates and balance alerts"
                enableLights(true)
                lightColor = Color.GREEN
                enableVibration(true)
                setBypassDnd(true)
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 1000, 200, 1000))
            .setAutoCancel(true)
            .build()
            
        val notificationId = if (title.contains("Updated")) (System.currentTimeMillis().toInt()) else 7005
        manager.notify(notificationId, notification)
    }

    private var currentWatchdogRunnable: Runnable? = null
    private var currentUpdateHandler: Handler? = null

    private fun startAutoUpdate() {
        if (isAutoUpdating) return
        val currentComp = CompanyDataHelper.getCurrentCompanyId(this) ?: "OKARA"
        val canonicalZone = CompanyDataHelper.getCanonicalDocId(currentComp)

        val docRef = getFranchiseDocRef()
        docRef.get().addOnSuccessListener { snapshot ->
            val enabledMap = snapshot.get("enabledCompanies") as? Map<*, *>
            autoUpdateQueue.clear()

            // Strict chip order matching ALL_COMPANIES: EBONE -> WATEEN -> ZONG -> JAZZ
            val companyOrder = listOf("EBONE", "WATEEN", "ZONG", "JAZZ")
            companyOrder.forEach { ispKey ->
                val isEnabled = (enabledMap?.get(ispKey) as? Boolean) ?: false
                if (isEnabled) {
                    autoUpdateQueue.add(ispKey to canonicalZone)
                }
            }

            if (autoUpdateQueue.isEmpty()) {
                Toast.makeText(this, "No ISP panels enabled for auto update in $canonicalZone", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            isAutoUpdating = true
            observeFranchiseBalances()

            // Failsafe watchdog timer (30 seconds max) to prevent getting stuck on "Updating..."
            val updateHandler = Handler(Looper.getMainLooper())
            val watchdogRunnable = Runnable {
                if (isAutoUpdating) {
                    isAutoUpdating = false
                    if (::swipeRefreshLayout.isInitialized) {
                        swipeRefreshLayout.isRefreshing = false
                    }
                    observeFranchiseBalances()
                    Toast.makeText(this@DealerPanelActivity, "Auto Update finished.", Toast.LENGTH_SHORT).show()
                }
            }
            updateHandler.postDelayed(watchdogRunnable, 30000L)
            currentWatchdogRunnable = watchdogRunnable
            currentUpdateHandler = updateHandler

            showStatusNotification("Auto Update Started", "ترتیب وار تمام پینلز کا بیلنس اپ ڈیٹ کیا جا رہا ہے ($canonicalZone)")
            Toast.makeText(this, "Starting sequential update for $canonicalZone...", Toast.LENGTH_SHORT).show()
            processNextAutoUpdate()
        }
    }

    private fun processNextAutoUpdate() {
        if (autoUpdateQueue.isEmpty()) {
            currentWatchdogRunnable?.let { currentUpdateHandler?.removeCallbacks(it) }
            isAutoUpdating = false
            observeFranchiseBalances()
            if (::swipeRefreshLayout.isInitialized) {
                swipeRefreshLayout.isRefreshing = false
            }
            showStatusNotification("Auto Update Completed", "تمام پینلز کامیابی سے اپ ڈیٹ ہو گئے ہیں")
            Toast.makeText(this, "Auto Update completed!", Toast.LENGTH_LONG).show()
            return
        }
        val next = autoUpdateQueue.removeAt(0)
        val isp = next.first
        val zone = next.second

        BackgroundBalanceUpdater.checkBalance(this, isp, zone) { balance ->
            runOnUiThread {
                if (balance != null) {
                    FranchiseBalanceManager.updateBalance(this, isp, balance, zone) {
                        FranchiseBalanceManager.showUpdateNotification(this, isp, balance, zone)
                    }
                } else {
                    showStatusNotification("$isp Update Failed", "بیلنس چیک کرنے میں دشواری پیش آئی")
                }

                if (autoUpdateQueue.isEmpty()) {
                    // Last panel just completed — show completion notification INSTANTLY!
                    currentWatchdogRunnable?.let { currentUpdateHandler?.removeCallbacks(it) }
                    isAutoUpdating = false
                    observeFranchiseBalances()
                    if (::swipeRefreshLayout.isInitialized) {
                        swipeRefreshLayout.isRefreshing = false
                    }
                    showStatusNotification("Auto Update Completed", "تمام پینلز کامیابی سے اپ ڈیٹ ہو گئے ہیں")
                    Toast.makeText(this@DealerPanelActivity, "Auto Update completed!", Toast.LENGTH_LONG).show()
                } else {
                    // More panels remaining, wait 500ms and process next
                    Handler(Looper.getMainLooper()).postDelayed({
                        processNextAutoUpdate()
                    }, 500L)
                }
            }
        }
    }

    private fun getFranchiseDocRef(): DocumentReference {
        val companyId = CompanyDataHelper.getCurrentCompanyId(this) ?: "OKARA"
        return db.collection("franchiseSettings").document(companyId)
    }

    private fun observeFranchiseBalances() {
        val companyId = CompanyDataHelper.getCurrentCompanyId(this) ?: "OKARA"
        val canonicalId = CompanyDataHelper.getCanonicalDocId(companyId)

        franchiseBalancesListener = getFranchiseDocRef()
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    processBalanceSnapshot(snapshot)
                } else {
                    db.collection("franchiseSettings").get().addOnSuccessListener { query ->
                        val matchingDoc = query.documents.find { doc ->
                            val dId = doc.id.trim().uppercase()
                            val dCity = doc.getString("cityName")?.trim()?.uppercase().orEmpty()
                            dId == companyId.uppercase() || dId == companyId.replace("-", "").uppercase() ||
                            dId == canonicalId || dCity == canonicalId || dCity == companyId.uppercase()
                        }
                        if (matchingDoc != null && matchingDoc.exists()) {
                            processBalanceSnapshot(matchingDoc)
                        } else {
                            renderEmptyBalances()
                        }
                    }.addOnFailureListener {
                        renderEmptyBalances()
                    }
                }
            }
    }

    private fun renderEmptyBalances() {
        franchiseBalancesRow.removeAllViews()
        franchiseBalancesRow.addView(emptyState("No balance data found"))
    }

    private fun processBalanceSnapshot(snapshot: DocumentSnapshot) {
        val enabledMap = snapshot.get("enabledCompanies") as? Map<*, *>

        franchiseBalancesRow.removeAllViews()
        val cityName = snapshot.getString("cityName")?.trim()?.takeIf { it.isNotBlank() } ?: CompanyDataHelper.getCurrentCompanyId(this) ?: "Franchise"

        val ebone = snapshot.getDouble("eboneBalance")
        val wateen = snapshot.getDouble("wateenBalance")
        val zong = snapshot.getDouble("zongBalance")
        val jazz = snapshot.getDouble("jazzBalance")
        
        val eboneTh = snapshot.getDouble("eboneLowBalanceThreshold") ?: 0.0
        val wateenTh = snapshot.getDouble("wateenLowBalanceThreshold") ?: 0.0
        val zongTh = snapshot.getDouble("zongLowBalanceThreshold") ?: 0.0
        val jazzTh = snapshot.getDouble("jazzLowBalanceThreshold") ?: 0.0

        val companyOrder = listOf("EBONE", "WATEEN", "ZONG", "JAZZ")
        companyOrder.forEach { ispKey ->
            val isEnabled = (enabledMap?.get(ispKey) as? Boolean) ?: false
            if (isEnabled) {
                when (ispKey) {
                    "EBONE" -> franchiseBalancesRow.addView(franchiseBalanceChip("Ebone", ebone, orange, "EBONE", cityName, eboneTh))
                    "WATEEN" -> franchiseBalancesRow.addView(franchiseBalanceChip("Wateen", wateen, navyMid, "WATEEN", cityName, wateenTh))
                    "ZONG" -> franchiseBalancesRow.addView(franchiseBalanceChip("Zong", zong, purple, "ZONG", cityName, zongTh))
                    "JAZZ" -> franchiseBalancesRow.addView(franchiseBalanceChip("Jazz", jazz, Color.parseColor("#E11D48"), "JAZZ", cityName, jazzTh))
                }
            }
        }

        lastDealerQuery?.let { renderDealers(it) }
    }

    private fun franchiseBalanceChip(label: String, value: Double?, accent: Int, isp: String = "", zone: String = "Okara", threshold: Double? = 0.0): LinearLayout {
        // Use absolute value for comparison because some panels show negative balance for credit
        val absValue = if (value != null) abs(value) else 0.0
        val safeThreshold = threshold ?: 0.0
        val isLow = value != null && safeThreshold > 0 && absValue < safeThreshold
        val bgColor = if (isLow) Color.parseColor("#FEE2E2") else Color.parseColor("#F9FAFB")
        val strokeColor = if (isLow) Color.parseColor("#EF4444") else borderLight
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = outlinedPill(bgColor, strokeColor, 10)
            layoutParams = LinearLayout.LayoutParams(dp(125), -2).also { it.marginEnd = dp(8) }
            
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = AppCompatResources.getDrawable(context, outValue.resourceId)
            isClickable = true
            isFocusable = true
            
            addView(TextView(this@DealerPanelActivity).apply {
                text = "$label\n${if(zone == "Okara") "Franchise" else zone}"
                textSize = 10f
                setTextColor(if (isLow) Color.parseColor("#B91C1C") else accent)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                val displayValue = if (value == null) "Not checked yet" else "Rs. ${"%,.0f".format(
                    abs(value)
                )}"
                text = displayValue
                textSize = 13f
                setTextColor(if (isLow) Color.parseColor("#B91C1C") else textDark)
                setTypeface(null, Typeface.BOLD)
            })
            setOnClickListener {
                launchCheckBalance(isp, zone)
            }
        }
    }

    private fun autoUpdateChip(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val bgColor = if (isAutoUpdating) Color.parseColor("#F5F3FF") else Color.parseColor("#EEF2FF")
            val borderColor = if (isAutoUpdating) Color.parseColor("#7C3AED") else Color.parseColor("#4F46E5")
            val textColor = if (isAutoUpdating) Color.parseColor("#7C3AED") else Color.parseColor("#4F46E5")
            background = outlinedPill(bgColor, borderColor, 10)
            layoutParams = LinearLayout.LayoutParams(dp(125), -2).also { it.marginEnd = dp(8) }
            addView(TextView(this@DealerPanelActivity).apply {
                text = if (isAutoUpdating) "⌛" else "⚡"
                textSize = 18f
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = if (isAutoUpdating) "Updating..." else "Auto Update"
                textSize = 10f
                setTextColor(textColor)
                setTypeface(null, Typeface.BOLD)
            })
            setOnClickListener { startAutoUpdate() }
        }

    private fun updateModeChip(): LinearLayout {
        val prefs = getSharedPreferences("dealer_panel_prefs", MODE_PRIVATE)
        isBackgroundMode = prefs.getBoolean("is_background_mode", false)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val bgColor = if (isBackgroundMode) Color.parseColor("#FDF2F2") else Color.parseColor("#ECFDF5")
            val textColor = if (isBackgroundMode) Color.parseColor("#991B1B") else Color.parseColor("#065F46")
            background = outlinedPill(bgColor, textColor, 10)
            layoutParams = LinearLayout.LayoutParams(dp(125), -2).also { it.marginEnd = dp(8) }
            val icon = TextView(this@DealerPanelActivity).apply {
                text = if (isBackgroundMode) "👤" else "👁️"
                textSize = 18f
            }
            val label = TextView(this@DealerPanelActivity).apply {
                text = if (isBackgroundMode) "Background" else "WebView"
                textSize = 10f
                setTextColor(textColor)
                setTypeface(null, Typeface.BOLD)
            }
            addView(icon)
            addView(label)
            setOnClickListener {
                isBackgroundMode = !isBackgroundMode
                prefs.edit().putBoolean("is_background_mode", isBackgroundMode).apply()
                observeFranchiseBalances() 
                val msg = if (isBackgroundMode) "Auto Update اب بیک گراؤنڈ میں چلے گا" else "Auto Update اب ویب ویو میں چلے گا"
                Toast.makeText(this@DealerPanelActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showThresholdSettingsDialog() {
        val docRef = getFranchiseDocRef()
        docRef.get().addOnSuccessListener { snapshot ->
            val enabledMap = snapshot.get("enabledCompanies") as? Map<*, *>
            val eboneEnabled = (enabledMap?.get("EBONE") as? Boolean) ?: false
            val wateenEnabled = (enabledMap?.get("WATEEN") as? Boolean) ?: false
            val zongEnabled = (enabledMap?.get("ZONG") as? Boolean) ?: false
            val jazzEnabled = (enabledMap?.get("JAZZ") as? Boolean) ?: false

            val cityName = snapshot.getString("cityName")?.trim()?.takeIf { it.isNotBlank() } ?: CompanyDataHelper.getCurrentCompanyId(this) ?: "Franchise"

            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(TextView(this@DealerPanelActivity).apply {
                    text = "Balance limits set karein ($cityName). Jab balance is se kam hoga to chip red ho jayegi."
                    textSize = 12f
                    setTextColor(textMuted)
                    setPadding(0, 0, 0, dp(12))
                })
            }

            var eboneInput: EditText? = null
            var wateenInput: EditText? = null
            var zongInput: EditText? = null
            var jazzInput: EditText? = null

            if (eboneEnabled) {
                eboneInput = EditText(this).apply {
                    hint = "Ebone alert limit (Rs.)"
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText((snapshot.getDouble("eboneLowBalanceThreshold") ?: 0.0).let { if (it > 0) it.toLong().toString() else "" })
                }
                box.addView(eboneInput)
            }
            if (wateenEnabled) {
                wateenInput = EditText(this).apply {
                    hint = "Wateen alert limit (Rs.)"
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText((snapshot.getDouble("wateenLowBalanceThreshold") ?: 0.0).let { if (it > 0) it.toLong().toString() else "" })
                }
                box.addView(wateenInput)
            }
            if (zongEnabled) {
                zongInput = EditText(this).apply {
                    hint = "Zong alert limit (Rs.)"
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText((snapshot.getDouble("zongLowBalanceThreshold") ?: 0.0).let { if (it > 0) it.toLong().toString() else "" })
                }
                box.addView(zongInput)
            }
            if (jazzEnabled) {
                jazzInput = EditText(this).apply {
                    hint = "Jazz alert limit (Rs.)"
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText((snapshot.getDouble("jazzLowBalanceThreshold") ?: 0.0).let { if (it > 0) it.toLong().toString() else "" })
                }
                box.addView(jazzInput)
            }

            AlertDialog.Builder(this)
                .setTitle("Low Balance Alerts ($cityName)")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val updates = mutableMapOf<String, Any>()
                    if (eboneEnabled && eboneInput != null) {
                        updates["eboneLowBalanceThreshold"] = eboneInput.text.toString().trim().toDoubleOrNull() ?: 0.0
                    }
                    if (wateenEnabled && wateenInput != null) {
                        updates["wateenLowBalanceThreshold"] = wateenInput.text.toString().trim().toDoubleOrNull() ?: 0.0
                    }
                    if (zongEnabled && zongInput != null) {
                        updates["zongLowBalanceThreshold"] = zongInput.text.toString().trim().toDoubleOrNull() ?: 0.0
                    }
                    if (jazzEnabled && jazzInput != null) {
                        updates["jazzLowBalanceThreshold"] = jazzInput.text.toString().trim().toDoubleOrNull() ?: 0.0
                    }
                    if (updates.isNotEmpty()) {
                        docRef.set(updates, SetOptions.merge())
                            .addOnSuccessListener { Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showZoneServiceSettingsDialog() {
        val currentComp = CompanyDataHelper.getCurrentCompanyId(this) ?: "OKARA"
        val canonicalZone = CompanyDataHelper.getCanonicalDocId(currentComp)
        showZoneServiceTogglesFor(canonicalZone)
    }

    private fun showZoneServiceTogglesFor(zone: String) {
        val docRef = getFranchiseDocRef()
        docRef.get().addOnSuccessListener { snapshot ->
            val enabledMap = snapshot.get("enabledCompanies") as? Map<*, *>
            val eboneSwitch = Switch(this).apply { text = "Ebone"; isChecked = (enabledMap?.get("EBONE") as? Boolean) ?: false }
            val wateenSwitch = Switch(this).apply { text = "Wateen"; isChecked = (enabledMap?.get("WATEEN") as? Boolean) ?: false }
            val zongSwitch = Switch(this).apply { text = "Zong"; isChecked = (enabledMap?.get("ZONG") as? Boolean) ?: false }
            val jazzSwitch = Switch(this).apply { text = "Jazz"; isChecked = (enabledMap?.get("JAZZ") as? Boolean) ?: false }

            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(TextView(this@DealerPanelActivity).apply {
                    text = "Turn off any service not running yet for $zone. Dealers in this zone won't be able to select a disabled service."
                    textSize = 12f
                    setTextColor(textMuted)
                    setPadding(0, 0, 0, dp(12))
                })
                addView(eboneSwitch)
                addView(wateenSwitch)
                addView(zongSwitch)
                addView(jazzSwitch)
            }
            AlertDialog.Builder(this)
                .setTitle("$zone — Enabled Services")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val updatedCompanies = mapOf(
                        "EBONE" to eboneSwitch.isChecked,
                        "WATEEN" to wateenSwitch.isChecked,
                        "ZONG" to zongSwitch.isChecked,
                        "JAZZ" to jazzSwitch.isChecked
                    )
                    docRef.set(mapOf("enabledCompanies" to updatedCompanies), SetOptions.merge())
                        .addOnSuccessListener {
                            CityPermissionManager.clearCache(this)
                            Toast.makeText(this, "$zone services updated", Toast.LENGTH_SHORT).show()
                            observeFranchiseBalances()
                        }
                        .addOnFailureListener { e -> Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }.addOnFailureListener { e -> Toast.makeText(this, "Could not load $zone settings: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun scanNow() {
        Toast.makeText(this, "Scanning SMS inbox for dealer payments…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val matched = DealerPaymentSmsScanner.scanAllPending(this@DealerPanelActivity, "manual_scan_now_button")
            runOnUiThread { Toast.makeText(this@DealerPanelActivity, if (matched > 0) "Matched $matched payment(s)" else "No new matches found", Toast.LENGTH_LONG).show() }
        }
    }

    private val zoneNames = listOf("Okara", "Renala")

    private fun showAddDealerDialog() {
        val currentComp = CompanyDataHelper.getCurrentCompanyId(this) ?: "OKARA"
        val canonicalZone = CompanyDataHelper.getCanonicalDocId(currentComp)

        val nameInput = EditText(this).apply { hint = "Dealer Name" }
        val mobileInput = EditText(this).apply { hint = "Mobile" }
        val eboneIdInput = EditText(this).apply { hint = "Ebone Panel Dealer ID" }
        val wateenIdInput = EditText(this).apply { hint = "Wateen Panel Dealer ID" }
        val zongIdInput = EditText(this).apply { hint = "Zong Panel Dealer ID" }
        val jazzIdInput = EditText(this).apply { hint = "Jazz Panel Dealer ID" }
        val code = (100000..999999).random().toString()

        val docRef = getFranchiseDocRef()
        docRef.get().addOnSuccessListener { snapshot ->
            val enabledMap = snapshot.get("enabledCompanies") as? Map<*, *>
            val eboneEnabled = (enabledMap?.get("EBONE") as? Boolean) ?: false
            val wateenEnabled = (enabledMap?.get("WATEEN") as? Boolean) ?: false
            val zongEnabled = (enabledMap?.get("ZONG") as? Boolean) ?: false
            val jazzEnabled = (enabledMap?.get("JAZZ") as? Boolean) ?: false

            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(nameInput)
                addView(mobileInput)
                addView(TextView(this@DealerPanelActivity).apply { text = "Franchise: $canonicalZone"; textSize = 12f; setTextColor(textMuted); setPadding(0, dp(14), 0, dp(4)) })
                addView(TextView(this@DealerPanelActivity).apply { text = "ISP Panel Dealer IDs (leave blank if not known yet)"; textSize = 12f; setTextColor(textMuted); setPadding(0, dp(14), 0, dp(4)) })
                if (eboneEnabled) addView(eboneIdInput)
                if (wateenEnabled) {
                    addView(LinearLayout(this@DealerPanelActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(wateenIdInput.also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                        addView(Button(this@DealerPanelActivity).apply {
                            text = "🔎 Fetch"
                            textSize = 12f
                            setOnClickListener {
                                val name = nameInput.text.toString().trim()
                                if (name.isEmpty()) Toast.makeText(this@DealerPanelActivity, "Type the dealer's name first", Toast.LENGTH_SHORT).show()
                                else fetchWateenDealerId(wateenIdInput, name)
                            }
                        })
                    })
                }
                if (zongEnabled) addView(zongIdInput)
                if (jazzEnabled) addView(jazzIdInput)
                addView(TextView(this@DealerPanelActivity).apply { text = "Code: $code"; textSize = 16f; setPadding(0, dp(14), 0, 0) })
            }

            AlertDialog.Builder(this).setTitle("Add / Update Dealer ($canonicalZone)").setView(box).setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val mobile = mobileInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Dealer name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val eboneIdVal = if (eboneEnabled) eboneIdInput.text.toString().trim() else ""
                val wateenIdVal = if (wateenEnabled) wateenIdInput.text.toString().trim() else ""
                val zongIdVal = if (zongEnabled) zongIdInput.text.toString().trim() else ""
                val jazzIdVal = if (jazzEnabled) jazzIdInput.text.toString().trim() else ""

                // Check if a dealer with this name already exists in this zone to prevent duplicate cards
                db.collection("dealers")
                    .whereEqualTo("zone", canonicalZone)
                    .get()
                    .addOnSuccessListener { existingSnap ->
                        val existingDoc = existingSnap.documents.firstOrNull { doc ->
                            val existingName = doc.getString("name") ?: ""
                            existingName.equals(name, ignoreCase = true)
                        }

                        if (existingDoc != null) {
                            // Merge/update into the existing dealer card!
                            val updateMap = mutableMapOf<String, Any>()
                            if (mobile.isNotBlank()) updateMap["mobile"] = mobile
                            if (eboneIdVal.isNotBlank()) updateMap["eboneDealerId"] = eboneIdVal
                            if (wateenIdVal.isNotBlank()) updateMap["wateenDealerId"] = wateenIdVal
                            if (zongIdVal.isNotBlank()) updateMap["zongDealerId"] = zongIdVal
                            if (jazzIdVal.isNotBlank()) updateMap["jazzDealerId"] = jazzIdVal

                            existingDoc.reference.update(updateMap).addOnSuccessListener {
                                FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
                                    .getReference("dealers")
                                    .child(existingDoc.id)
                                    .updateChildren(updateMap)
                                Toast.makeText(this, "Updated '$name' on existing card ($canonicalZone)", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // Create new single dealer document
                            val dealerId = db.collection("dealers").document().id
                            val dealer = mapOf(
                                "dealerId" to dealerId,
                                "name" to name,
                                "mobile" to mobile,
                                "dealerCode" to code,
                                "zone" to canonicalZone,
                                "companyId" to currentComp,
                                "company" to currentComp,
                                "companyCode" to currentComp,
                                "eboneDealerId" to eboneIdVal,
                                "wateenDealerId" to wateenIdVal,
                                "zongDealerId" to zongIdVal,
                                "jazzDealerId" to jazzIdVal,
                                "eboneBalance" to 0.0,
                                "wateenBalance" to 0.0,
                                "zongBalance" to 0.0,
                                "jazzBalance" to 0.0,
                                "createdAt" to System.currentTimeMillis()
                            )
                            db.collection("dealers").document(dealerId).set(dealer).addOnSuccessListener {
                                FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")
                                    .getReference("dealers")
                                    .child(dealerId)
                                    .setValue(dealer)
                                Toast.makeText(this, "Dealer '$name' created for $canonicalZone", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
            }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun resetDealerBalance(dealerId: String, name: String, zone: String) {
        AlertDialog.Builder(this)
            .setTitle("PERMANENT RESET: $name")
            .setMessage("⚠ CAUTION: This will permanently:\n" +
                    "1. Delete all Payment Logs\n" +
                    "2. Delete all Transactions\n" +
                    "3. Clear all Payment Claims (TIDs)\n" +
                    "4. Set Wallet Balance to zero\n\n" +
                    "This allows the dealer to re-upload and re-apply for the same payments. Proceed?")
            .setPositiveButton("Reset Everything") { _, _ ->
                val progress = ProgressBar(this).apply { isIndeterminate = true }
                val diag = AlertDialog.Builder(this).setTitle("Resetting...").setView(progress).setCancelable(false).show()

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val batch = db.batch()
                        
                        // 1. Reset Balance in dealers doc
                        val dealerRef = db.collection("dealers").document(dealerId)
                        batch.update(dealerRef, mapOf(
                            "eboneBalance" to 0.0,
                            "wateenBalance" to 0.0,
                            "zongBalance" to 0.0
                        ))

                        // 2. Clear dealerPayments
                        val paySnap = Tasks.await(db.collection("dealerPayments").whereEqualTo("dealerId", dealerId).get())
                        paySnap.documents.forEach { batch.delete(it.reference) }

                        // 3. Clear dealerTransactions
                        val txnSnap = Tasks.await(db.collection("dealerTransactions").whereEqualTo("dealerId", dealerId).get())
                        txnSnap.documents.forEach { batch.delete(it.reference) }

                        // 4. Clear paymentClaims (Matching TIDs)
                        val claimSnap = Tasks.await(db.collection("paymentClaims").whereEqualTo("ownerId", dealerId).get())
                        claimSnap.documents.forEach { batch.delete(it.reference) }

                        // Commit all deletions at once
                        Tasks.await(batch.commit())

                        runOnUiThread {
                            diag.dismiss()
                            Toast.makeText(this@DealerPanelActivity, "$name has been fully reset and synced.", Toast.LENGTH_LONG).show()
                            // observeDealers() is not needed as Batch update will trigger SnapshotListener
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            diag.dismiss()
                            Toast.makeText(this@DealerPanelActivity, "Reset failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDealerLedger(dealerId: String, name: String, zone: String) {
        val ledgerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Ledger: $name ($zone)"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textDark)
            setPadding(0, 0, 0, dp(16))
        }
        ledgerLayout.addView(title)

        val resultContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }

        val filterRow = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dateBtn = Button(this).apply {
            text = "🗓️ Range"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(4) }
            setOnClickListener { openLedgerDatePicker(dealerId, resultContainer) }
        }
        val thirtyDaysBtn = Button(this).apply {
            text = "30 Days"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(4) }
            setOnClickListener { loadLedgerForRange(dealerId, resultContainer, System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000), System.currentTimeMillis()) }
        }
        val resetBtn = Button(this).apply {
            text = "Reset 🔄"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C62828"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { resetDealerBalance(dealerId, name, zone) }
        }
        filterRow.addView(dateBtn)
        filterRow.addView(thirtyDaysBtn)
        filterRow.addView(resetBtn)
        ledgerLayout.addView(filterRow)

        ledgerLayout.addView(resultContainer)

        val dialog = AlertDialog.Builder(this)
            .setView(ledgerLayout)
            .setNegativeButton("Close", null)
            .setNeutralButton("Settings") { _, _ -> showDealerDetails(dealerId) }
            .create()
        dialog.show()
            
        // Initial load: Last 30 days
        loadLedgerForRange(dealerId, resultContainer, System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000), System.currentTimeMillis())
    }

    private fun openLedgerDatePicker(dealerId: String, container: LinearLayout) {
        val builder = MaterialDatePicker.Builder.dateRangePicker()
        builder.setTitleText("Select Ledger Dates")
        val picker = builder.build()
        picker.show(supportFragmentManager, "LEDGER_RANGE")
        picker.addOnPositiveButtonClickListener { selection ->
            val start = selection.first
            val end = selection.second + (24 * 60 * 60 * 1000) - 1
            loadLedgerForRange(dealerId, container, start, end)
        }
    }

    private data class DealerLedgerItem(val type: String, val panel: String, val amount: Double, val date: Long)

    private fun loadLedgerForRange(dealerId: String, container: LinearLayout, start: Long, end: Long) {
        container.removeAllViews()
        container.addView(ProgressBar(this).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also { it.gravity = Gravity.CENTER } })

        val df = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val allEntries = mutableListOf<DealerLedgerItem>()

        val tasks = mutableListOf<Task<QuerySnapshot>>()
        
        // 1. dealerPayments
        tasks.add(db.collection("dealerPayments")
            .whereEqualTo("dealerId", dealerId)
            .get())

        // 2. dealerTransactions
        tasks.add(db.collection("dealerTransactions")
            .whereEqualTo("dealerId", dealerId)
            .get())

        // 3. paymentClaims (Dealer claims)
        tasks.add(db.collection("paymentClaims")
            .whereEqualTo("ownerId", dealerId)
            .get())

        Tasks.whenAllComplete(tasks).addOnSuccessListener {
            container.removeAllViews()
            
            tasks.forEachIndexed { index, task ->
                if (task.isSuccessful) {
                    task.result?.documents?.forEach { doc ->
                        val date = doc.getLong("submittedAt")
                            ?: doc.getLong("timestamp")
                            ?: doc.getLong("createdAt")
                            ?: 0L
                        
                        if (date in start..end) {
                            val amt = doc.getDouble("amount") ?: 0.0
                            val status = doc.getString("status") ?: "COMPLETED"
                            val ownerType = doc.getString("ownerType") ?: "DEALER"

                            if (index == 1 && status != "COMPLETED" && status != "VERIFIED") {
                                // Skip non-completed transactions
                                return@forEach
                            }
                            if (index == 2 && !ownerType.equals("DEALER", ignoreCase = true)) {
                                return@forEach
                            }

                            val type = when(index) {
                                0 -> "Payment"
                                1 -> "Txn"
                                else -> "Claim"
                            }
                            val panel = when(index) {
                                2 -> doc.getString("paymentSource") ?: ""
                                else -> doc.getString("panel") ?: ""
                            }
                            allEntries.add(DealerLedgerItem(type, panel, amt, date))
                        }
                    }
                }
            }

            allEntries.sortByDescending { it.date }

            if (allEntries.isEmpty()) {
                container.addView(TextView(this).apply { text = "No records found for this dealer."; gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) })
                return@addOnSuccessListener
            }

            var total = 0.0
            allEntries.forEach { entry ->
                total += entry.amount
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, dp(10))
                    gravity = Gravity.CENTER_VERTICAL
                }
                
                val typeTag = TextView(this).apply {
                    text = entry.type
                    textSize = 9f
                    setTextColor(Color.WHITE)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    background = pill(when(entry.type) {
                        "Payment" -> orange
                        "Txn" -> navyMid
                        else -> purple
                    }, 4)
                    layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(8) }
                }
                row.addView(typeTag)

                val details = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                details.addView(TextView(this@DealerPanelActivity).apply {
                    text = "${entry.panel} Panel"
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textDark)
                })
                details.addView(TextView(this@DealerPanelActivity).apply {
                    text = df.format(Date(entry.date))
                    textSize = 11f
                    setTextColor(textMuted)
                })
                row.addView(details)

                row.addView(TextView(this).apply {
                    text = "Rs. ${"%,.0f".format(entry.amount)}"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(green)
                })
                
                container.addView(row)
                container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(1)); setBackgroundColor(Color.parseColor("#F2F4F7")) })
            }

            container.addView(TextView(this).apply {
                text = "Total Record: Rs. ${"%,.0f".format(total)}"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textDark)
                gravity = Gravity.END
                setPadding(0, dp(16), 0, dp(8))
            })
        }
    }

    private fun showDealerDetails(dealerId: String) {
        db.collection("dealers").document(dealerId).get().addOnSuccessListener { document ->
            if (!document.exists()) return@addOnSuccessListener
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
            val activeSwitch = Switch(this).apply { text = "Dealer Active"; isChecked = (document.getString("status") ?: "ACTIVE") == "ACTIVE" }
            box.addView(activeSwitch)
            box.addView(TextView(this@DealerPanelActivity).apply { text = "Zone (Franchise Tag)"; textSize = 12f; setTextColor(textMuted); setPadding(0, dp(10), 0, dp(4)) })
            val currentZone = document.getString("zone")?.ifBlank { null } ?: "Okara"
            val zoneSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@DealerPanelActivity, R.layout.simple_spinner_dropdown_item, zoneNames); setSelection(zoneNames.indexOf(currentZone).coerceAtLeast(0)) }
            box.addView(zoneSpinner)
            val savedAccounts = document.get("paymentAccounts") as? Map<*, *> ?: emptyMap<Any, Any>()
            val switches = mutableMapOf<String, Switch>()
            paymentAccountNames.forEach { accountName -> val accountSwitch = Switch(this).apply { text = accountName; isChecked = savedAccounts[accountName] != false }; switches[accountName] = accountSwitch; box.addView(accountSwitch) }
            AlertDialog.Builder(this).setTitle("Dealer Settings").setView(box).setPositiveButton("Save") { _, _ ->
                val accountStates = switches.mapValues { it.value.isChecked }
                val selectedZone = zoneNames[zoneSpinner.selectedItemPosition]
                db.collection("dealers").document(dealerId).update(mapOf("status" to if (activeSwitch.isChecked) "ACTIVE" else "DISABLED", "zone" to selectedZone, "paymentAccounts" to accountStates)).addOnFailureListener { error -> Toast.makeText(this, "Save failed: ${error.message}", Toast.LENGTH_LONG).show() }
            }.setNegativeButton("Close", null).show()
        }.addOnFailureListener { error -> Toast.makeText(this, "Dealer details load failed: ${error.message}", Toast.LENGTH_LONG).show() }
    }
}
