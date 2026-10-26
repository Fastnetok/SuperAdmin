package com.example.superadmin

import android.R
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.superadmin.core.CompanyDataHelper
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.*

/**
 * Premium Dealer Recharge Report with:
 *  - Material Date Range Picker (The requested Advance Calendar)
 *  - ISP Specific Filtering (Border highlight on tap)
 *  - Persistent Soft Delete (Swipe to Hide) with accurate count updates
 *  - Independent Summary Cards (Totals don't zero out when filtering)
 */
class DealerRechargeReportActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ReportAdapter
    private lateinit var totalText: TextView
    private lateinit var filterLabel: TextView
    
    private lateinit var summaryEbone: TextView
    private lateinit var summaryWateen: TextView
    private lateinit var summaryZong: TextView
    private lateinit var summaryJazz: TextView
    
    private lateinit var cardEbone: LinearLayout
    private lateinit var cardWateen: LinearLayout
    private lateinit var cardZong: LinearLayout
    private lateinit var cardJazz: LinearLayout
    private lateinit var dealerFilterChip: TextView

    private var rangeStart: Long = 0
    private var rangeEnd: Long = 0
    private var activeIspFilter: String? = null
    private var activeDealerFilter: String? = null
    
    private val masterList = mutableListOf<ReportEntry>()
    private val hiddenIds = mutableSetOf<String>()

    private val textDark = Color.parseColor("#172033")
    private val textMuted = Color.parseColor("#667085")
    private val borderLight = Color.parseColor("#E4E7EC")
    private val accentBlue = Color.parseColor("#1D4ED8")
    private val green = Color.parseColor("#12B76A")
    private val orange = Color.parseColor("#F79009")
    private val navyMid = Color.parseColor("#1D4ED8")
    private val purple = Color.parseColor("#7A5AF8")

    data class ReportEntry(
        val id: String,
        val dealerName: String,
        val panel: String,
        val amount: Double,
        val timestamp: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())

        loadHiddenIds()
        setupRecyclerView()
        setTodayRange()
        loadReport()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6FA"))
        }

        // Header
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0D2E5C"), Color.parseColor("#1D4ED8")))
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Dealer Report"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu_rotate)
            background = null
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setOnClickListener { 
                hiddenIds.clear()
                saveHiddenIds()
                updateDisplayList()
                Toast.makeText(this@DealerRechargeReportActivity, "Data Restored", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(header)

        // Filter Chips
        val filterScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        dealerFilterChip = filterChip("👤 All Dealers") { pickDealerFilter() }
        chips.addView(dealerFilterChip)
        chips.addView(filterChip("Today") { setTodayRange(); loadReport() })
        chips.addView(filterChip("Yesterday") { setYesterdayRange(); loadReport() })
        chips.addView(filterChip("7 Days") { set7DaysRange(); loadReport() })
        chips.addView(filterChip("This Month") { setThisMonthRange(); loadReport() })
        chips.addView(filterChip("Last Month") { setLastMonthRange(); loadReport() })
        chips.addView(filterChip("🗓️ Custom Range") { pickCustomRange() })
        filterScroll.addView(chips)
        root.addView(filterScroll)

        filterLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(navyMid)
            setPadding(dp(20), dp(8), dp(16), dp(16))
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(filterLabel)

        val currentZone = CompanyDataHelper.getCurrentCompanyId(this) ?: "OKARA"
        CityPermissionManager.getEnabledCompanies(this, currentZone) { enabledCompanies ->
            val summaryRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = enabledCompanies.size.toFloat().coerceAtLeast(1f)
                setPadding(dp(12), 0, dp(12), 0)
            }
            
            if (enabledCompanies.contains("EBONE")) {
                val (eC, eT) = createSummaryCard("EBONE", orange)
                cardEbone = eC; summaryEbone = eT
                cardEbone.setOnClickListener { toggleIspFilter("EBONE") }
                summaryRow.addView(cardEbone)
            }
            if (enabledCompanies.contains("WATEEN")) {
                val (wC, wT) = createSummaryCard("Wateen", navyMid)
                cardWateen = wC; summaryWateen = wT
                cardWateen.setOnClickListener { toggleIspFilter("Wateen") }
                summaryRow.addView(cardWateen)
            }
            if (enabledCompanies.contains("ZONG")) {
                val (zC, zT) = createSummaryCard("Zong", purple)
                cardZong = zC; summaryZong = zT
                cardZong.setOnClickListener { toggleIspFilter("Zong") }
                summaryRow.addView(cardZong)
            }
            if (enabledCompanies.contains("JAZZ")) {
                val (jC, jT) = createSummaryCard("Jazz", Color.parseColor("#E11D48"))
                cardJazz = jC; summaryJazz = jT
                cardJazz.setOnClickListener { toggleIspFilter("Jazz") }
                summaryRow.addView(cardJazz)
            }

            root.addView(summaryRow)
        }

        totalText = TextView(this).apply {
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textDark)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(totalText)

        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            setPadding(dp(16), 0, dp(16), dp(16))
            clipToPadding = false
        }
        root.addView(recyclerView)

        return root
    }

    private fun createSummaryCard(label: String, color: Int): Pair<LinearLayout, TextView> {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(6) }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = outlinedPill(Color.WHITE, borderLight, 14)
            elevation = dp(2).toFloat()
        }
        container.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(color)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
        val valText = TextView(this).apply {
            text = "Rs. 0"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textDark)
        }
        container.addView(valText)
        return container to valText
    }

    private fun filterChip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = outlinedPill(Color.WHITE, borderLight, 25)
        setTextColor(textDark)
        setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun setupRecyclerView() {
        adapter = ReportAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.adapterPosition
                val entry = adapter.currentList[pos]
                hiddenIds.add(entry.id)
                saveHiddenIds()
                updateDisplayList()
            }
            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, s: Int, a: Boolean) {
                val itemView = vh.itemView
                val bg = ColorDrawable(Color.parseColor("#FEE2E2"))
                if (dX > 0) bg.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                else bg.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                bg.draw(c)
                super.onChildDraw(c, rv, vh, dX, dY, s, a)
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(recyclerView)
    }

    private fun toggleIspFilter(isp: String) {
        activeIspFilter = if (activeIspFilter == isp) null else isp
        
        // Border highlight remains blue, background stays white
        if (::cardEbone.isInitialized) cardEbone.background = outlinedPill(Color.WHITE, if(activeIspFilter == "EBONE") accentBlue else borderLight, 14)
        if (::cardWateen.isInitialized) cardWateen.background = outlinedPill(Color.WHITE, if(activeIspFilter == "Wateen") accentBlue else borderLight, 14)
        if (::cardZong.isInitialized) cardZong.background = outlinedPill(Color.WHITE, if(activeIspFilter == "Zong") accentBlue else borderLight, 14)
        if (::cardJazz.isInitialized) cardJazz.background = outlinedPill(Color.WHITE, if(activeIspFilter == "Jazz") accentBlue else borderLight, 14)
        
        updateDisplayList()
    }

    private fun pickDealerFilter() {
        val uniqueDealers = masterList.map { it.dealerName }.distinct().sorted()
        if (uniqueDealers.isEmpty()) {
            Toast.makeText(this, "No dealers found in this date range", Toast.LENGTH_SHORT).show()
            return
        }
        val options = mutableListOf("All Dealers")
        options.addAll(uniqueDealers)

        AlertDialog.Builder(this)
            .setTitle("Filter by Dealer")
            .setItems(options.toTypedArray()) { _, which ->
                activeDealerFilter = if (which == 0) null else options[which]
                dealerFilterChip.text = if (activeDealerFilter == null) "👤 All Dealers" else "👤 $activeDealerFilter"
                updateDisplayList()
            }
            .show()
    }

    private fun setTodayRange() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        filterLabel.text = "Showing: Today (${formatDate(rangeStart)})"
    }

    private fun setYesterdayRange() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
        rangeEnd = cal.timeInMillis
        filterLabel.text = "Showing: Yesterday (${formatDate(rangeStart)})"
    }

    private fun set7DaysRange() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -7)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        filterLabel.text = "Showing: Last 7 Days (${formatDate(rangeStart)} - Now)"
    }

    private fun setThisMonthRange() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        filterLabel.text = "Showing: This Month (${formatDate(rangeStart)} - Now)"
    }

    private fun setLastMonthRange() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        rangeStart = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
        rangeEnd = cal.timeInMillis
        filterLabel.text = "Showing: Last Month (${formatDate(rangeStart)} - ${formatDate(rangeEnd)})"
    }

    private fun pickCustomRange() {
        val builder = MaterialDatePicker.Builder.dateRangePicker()
        builder.setTitleText("Select Dates")
        val picker = builder.build()
        picker.show(supportFragmentManager, "RANGE_PICKER")
        picker.addOnPositiveButtonClickListener { selection ->
            rangeStart = selection.first
            rangeEnd = selection.second + (24 * 60 * 60 * 1000) - 1
            filterLabel.text = "Custom: ${formatDate(rangeStart)} - ${formatDate(rangeEnd)}"
            loadReport()
        }
    }

    private fun formatDate(ms: Long): String = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))

    private fun loadReport() {
        masterList.clear()

        val onLoaded: () -> Unit = {
            masterList.sortByDescending { it.timestamp }
            updateDisplayList()
        }

        db.collection("dealerTransactions")
            .whereGreaterThanOrEqualTo("createdAt", rangeStart)
            .whereLessThanOrEqualTo("createdAt", rangeEnd)
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.documents.forEach { doc ->
                    if (!CompanyDataHelper.belongsToCurrentCompany(this@DealerRechargeReportActivity, doc)) return@forEach
                    val status = doc.getString("status") ?: ""
                    val transferStatus = doc.getString("transferStatus") ?: ""
                    if (status == "COMPLETED" || status == "VERIFIED" || transferStatus == "TRANSFERRED") {
                        val p = doc.getString("panel") ?: doc.getString("company") ?: ""
                        val ispName = when (p.uppercase()) {
                            "EBONE", "EBILL" -> "EBONE"
                            "WATEEN" -> "Wateen"
                            "ZONG" -> "Zong"
                            "JAZZ" -> "Jazz"
                            else -> p
                        }
                        val dName = doc.getString("dealerName") ?: doc.getString("name") ?: "Unknown Dealer"
                        val amt = doc.getDouble("amount") ?: 0.0
                        val ts = doc.getLong("createdAt") ?: doc.getLong("submittedAt") ?: System.currentTimeMillis()
                        masterList.add(ReportEntry(doc.id, dName, ispName, amt, ts))
                    }
                }

                db.collection("dealerPayments")
                    .whereGreaterThanOrEqualTo("submittedAt", rangeStart)
                    .whereLessThanOrEqualTo("submittedAt", rangeEnd)
                    .get()
                    .addOnSuccessListener { snap2 ->
                        snap2.documents.forEach { doc ->
                            if (!CompanyDataHelper.belongsToCurrentCompany(this@DealerRechargeReportActivity, doc)) return@forEach
                            if (masterList.none { it.id == doc.id }) {
                                val p = doc.getString("panel") ?: ""
                                val ispName = when (p.uppercase()) {
                                    "EBONE", "EBILL" -> "EBONE"
                                    "WATEEN" -> "Wateen"
                                    "ZONG" -> "Zong"
                                    "JAZZ" -> "Jazz"
                                    else -> p
                                }
                                val dName = doc.getString("dealerName") ?: "Unknown"
                                val amt = doc.getDouble("amount") ?: 0.0
                                val ts = doc.getLong("submittedAt") ?: System.currentTimeMillis()
                                masterList.add(ReportEntry(doc.id, dName, ispName, amt, ts))
                            }
                        }
                        onLoaded()
                    }.addOnFailureListener { onLoaded() }
            }.addOnFailureListener { onLoaded() }
    }

    private fun updateDisplayList() {
        // 1. Calculate Summary Card totals (always based on unhidden items, ignoring ISP filter)
        val unhiddenItems = masterList.filter { !hiddenIds.contains(it.id) }
        var totalE = 0.0; var totalW = 0.0; var totalZ = 0.0; var totalJ = 0.0
        
        unhiddenItems.forEach { 
            when(it.panel) {
                "EBONE" -> totalE += it.amount
                "Wateen" -> totalW += it.amount
                "Zong" -> totalZ += it.amount
                "Jazz" -> totalJ += it.amount
            }
        }
        
        if (::summaryEbone.isInitialized) summaryEbone.text = "Rs. ${"%,.0f".format(totalE)}"
        if (::summaryWateen.isInitialized) summaryWateen.text = "Rs. ${"%,.0f".format(totalW)}"
        if (::summaryZong.isInitialized) summaryZong.text = "Rs. ${"%,.0f".format(totalZ)}"
        if (::summaryJazz.isInitialized) summaryJazz.text = "Rs. ${"%,.0f".format(totalJ)}"
        
        // 2. Filter the RecyclerView list based on hidden items, active ISP selection AND active Dealer selection
        val filteredList = unhiddenItems.filter { 
            (activeIspFilter == null || it.panel == activeIspFilter) &&
            (activeDealerFilter == null || it.dealerName.equals(activeDealerFilter, true))
        }
        adapter.submitList(filteredList)
        
        // 3. Update the Combined Total at the bottom
        val grandTotal = filteredList.sumOf { it.amount }
        totalText.text = "Total Combined: Rs. ${"%,.0f".format(grandTotal)}"
    }

    private fun saveHiddenIds() {
        getSharedPreferences("report_prefs", MODE_PRIVATE).edit().putStringSet("hidden_ids", hiddenIds).apply()
    }

    private fun loadHiddenIds() {
        val saved = getSharedPreferences("report_prefs", MODE_PRIVATE).getStringSet("hidden_ids", emptySet())
        hiddenIds.clear(); hiddenIds.addAll(saved ?: emptySet())
    }

    private fun outlinedPill(bg: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(bg); setCornerRadius(dp(radius).toFloat()); setStroke(dp(2), stroke)
    }

    inner class ReportAdapter : RecyclerView.Adapter<ReportAdapter.VH>() {
        var currentList = listOf<ReportEntry>()
        fun submitList(list: List<ReportEntry>) { currentList = list; notifyDataSetChanged() }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(
            R.layout.simple_list_item_2, p, false))
        override fun getItemCount() = currentList.size
        override fun onBindViewHolder(h: VH, p: Int) {
            val item = currentList[p]
            val ctx = h.itemView.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14))
                background = outlinedPill(Color.WHITE, borderLight, 12)
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) }
                elevation = dp(1).toFloat()
            }
            val top = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(ctx).apply { text = item.dealerName; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(textDark); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            top.addView(TextView(ctx).apply { text = "Rs. ${"%,.0f".format(item.amount)}"; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(green) })
            row.addView(top)
            val bot = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0) }
            bot.addView(TextView(ctx).apply { 
                text = item.panel
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(when(item.panel) { 
                    "Wateen" -> navyMid
                    "Zong" -> purple
                    "Jazz" -> Color.parseColor("#E11D48")
                    else -> orange 
                }) 
            })
            bot.addView(TextView(ctx).apply { text = "  •  " + SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(item.timestamp)); textSize = 11f; setTextColor(textMuted) })
            row.addView(bot)
            (h.itemView as ViewGroup).apply { removeAllViews(); addView(row) }
        }
        inner class VH(v: View) : RecyclerView.ViewHolder(v)
    }
}
