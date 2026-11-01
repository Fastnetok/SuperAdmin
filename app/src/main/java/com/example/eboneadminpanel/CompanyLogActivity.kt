package com.example.superadmin

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.superadmin.models.Company
import com.example.superadmin.repositories.CompanyRepository
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CompanyLogActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val companyRepo = CompanyRepository()

    private lateinit var filterChipsContainer: LinearLayout
    private lateinit var spinnerCompanyFilter: Spinner
    private lateinit var scoreboardGraphContainer: LinearLayout
    private lateinit var tvRangeLabel: TextView
    private lateinit var tvStatUsers: TextView
    private lateinit var tvStatActions: TextView
    private lateinit var tvStatTopCompany: TextView
    private lateinit var tvStat1Label: TextView
    private lateinit var tvStat2Label: TextView
    private lateinit var tvStat3Label: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvSectionTitle: TextView
    private lateinit var rv: RecyclerView

    private lateinit var btnTabUsage: Button
    private lateinit var btnTabLicense: Button
    private lateinit var layoutFilterRow: LinearLayout
    private lateinit var layoutDateChips: LinearLayout
    private lateinit var layoutStatsCards: LinearLayout
    private lateinit var layoutScoreboardGraph: LinearLayout

    private val chipLabelFormat = SimpleDateFormat("dd MMM", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
    private val hourFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val fullDateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

    private var rangeStart: Long = 0L
    private var rangeEnd: Long = 0L
    private var selectedChip: TextView? = null

    private var currentTab = Tab.USAGE_ANALYTICS

    enum class Tab {
        USAGE_ANALYTICS,
        LICENSE_MANAGEMENT
    }

    private data class UserActivityLog(
        val name: String,
        val email: String,
        val role: String,
        val companyId: String,
        val usageCount: Int,
        val lastActive: Long,
        val loginTime: String,
        val logoutTime: String,
        val sessionDuration: String,
        val dailyMinutes: Int,
        val isOnline: Boolean
    )

    private var allLogs = listOf<UserActivityLog>()
    private var allCompaniesList = listOf<Company>()
    private var availableCompanies = listOf("All Companies")
    private var selectedCompanyFilter = "All Companies"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val currentUserEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase()
        if (currentUserEmail != "abbasok@gmail.com" && currentUserEmail?.contains("abbasok") != true) {
            Toast.makeText(this, "Access Denied: SuperAdmin Owner only.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        window.statusBarColor = Color.parseColor("#0D2E5C")
        setContentView(R.layout.activity_company_log)

        filterChipsContainer = findViewById(R.id.filterChipsContainer)
        spinnerCompanyFilter = findViewById(R.id.spinnerCompanyFilter)
        scoreboardGraphContainer = findViewById(R.id.scoreboardGraphContainer)
        tvRangeLabel = findViewById(R.id.tvLogRangeLabel)
        tvStatUsers = findViewById(R.id.tvStatUsers)
        tvStatActions = findViewById(R.id.tvStatActions)
        tvStatTopCompany = findViewById(R.id.tvStatTopCompany)
        tvStat1Label = findViewById(R.id.tvStat1Label)
        tvStat2Label = findViewById(R.id.tvStat2Label)
        tvStat3Label = findViewById(R.id.tvStat3Label)
        tvEmpty = findViewById(R.id.tvLogEmpty)
        tvSectionTitle = findViewById(R.id.tvSectionTitle)
        rv = findViewById(R.id.rvCompanyLog)
        rv.layoutManager = LinearLayoutManager(this)

        btnTabUsage = findViewById(R.id.btnTabUsage)
        btnTabLicense = findViewById(R.id.btnTabLicense)
        layoutFilterRow = findViewById(R.id.layoutFilterRow)
        layoutDateChips = findViewById(R.id.layoutDateChips)
        layoutStatsCards = findViewById(R.id.layoutStatsCards)
        layoutScoreboardGraph = findViewById(R.id.layoutScoreboardGraph)

        findViewById<ImageButton>(R.id.btnLogBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnLogCalendar).setOnClickListener { openCustomRangePicker() }

        btnTabUsage.setOnClickListener { switchTab(Tab.USAGE_ANALYTICS) }
        btnTabLicense.setOnClickListener { switchTab(Tab.LICENSE_MANAGEMENT) }

        buildQuickFilterChips()
        selectQuickRange(daysBack = 0, label = "Today")
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        if (tab == Tab.USAGE_ANALYTICS) {
            btnTabUsage.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1976D2"))
            btnTabLicense.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#37474F"))
            layoutFilterRow.visibility = View.VISIBLE
            layoutDateChips.visibility = View.VISIBLE
            layoutStatsCards.visibility = View.VISIBLE
            layoutScoreboardGraph.visibility = View.VISIBLE
            tvStat1Label.text = "Active Users"
            tvStat2Label.text = "Total Actions"
            tvStat3Label.text = "Top Company"
            tvSectionTitle.text = "  Usage Ranking & Logs (Admins, Managers & Supervisors)"
            renderLogs()
        } else {
            btnTabLicense.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1976D2"))
            btnTabUsage.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#37474F"))
            layoutFilterRow.visibility = View.GONE
            layoutDateChips.visibility = View.GONE
            layoutStatsCards.visibility = View.VISIBLE
            layoutScoreboardGraph.visibility = View.GONE
            tvStat1Label.text = "Total Licenses"
            tvStat2Label.text = "Active Licenses"
            tvStat3Label.text = "Expired / Soon"
            tvSectionTitle.text = "  Company License Management & 12:00 PM Noon Expiry Tracking"
            loadCompaniesForLicenseManagement()
        }
    }

    private fun buildQuickFilterChips() {
        val options = listOf(
            "Today" to 0,
            "Yesterday" to 1,
            "2 Days" to 2,
            "Last 7 Days" to 7,
            "Last 30 Days" to 30
        )

        options.forEach { (label, daysBack) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor("#444444"))
                setPadding(36, 18, 36, 18)
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@CompanyLogActivity, R.drawable.bg_log_filter_chip
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 20 }

                setOnClickListener {
                    if (label == "Last 30 Days") {
                        selectThisMonth(this)
                    } else if (label == "Yesterday") {
                        selectQuickRange(daysBack = 1, label = label, singleDay = true, selectedView = this)
                    } else {
                        selectQuickRange(daysBack = daysBack, label = label, selectedView = this)
                    }
                }
            }
            filterChipsContainer.addView(chip)
        }
    }

    private fun selectQuickRange(daysBack: Int, label: String, singleDay: Boolean = false, selectedView: TextView? = null) {
        val calStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (daysBack > 0) add(Calendar.DAY_OF_YEAR, -daysBack)
        }
        val calEnd = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
            if (singleDay && daysBack > 0) {
                add(Calendar.DAY_OF_YEAR, -daysBack)
            }
        }

        rangeStart = calStart.timeInMillis
        rangeEnd = calEnd.timeInMillis

        tvRangeLabel.text = if (daysBack == 0) "Today (${chipLabelFormat.format(calStart.time)})" else "$label (${chipLabelFormat.format(calStart.time)} – ${chipLabelFormat.format(calEnd.time)})"

        selectedChip?.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_log_filter_chip)
        selectedChip?.setTextColor(Color.parseColor("#444444"))

        selectedView?.let {
            it.setBackgroundColor(Color.parseColor("#0D2E5C"))
            it.setTextColor(Color.WHITE)
            selectedChip = it
        }

        if (currentTab == Tab.USAGE_ANALYTICS) {
            loadCompanyLogs()
        }
    }

    private fun selectThisMonth(selectedView: TextView) {
        val start = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }

        rangeStart = start.timeInMillis
        rangeEnd = end.timeInMillis
        tvRangeLabel.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(start.time)

        selectedChip?.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_log_filter_chip)
        selectedChip?.setTextColor(Color.parseColor("#444444"))
        selectedView.setBackgroundColor(Color.parseColor("#0D2E5C"))
        selectedView.setTextColor(Color.WHITE)
        selectedChip = selectedView

        if (currentTab == Tab.USAGE_ANALYTICS) {
            loadCompanyLogs()
        }
    }

    private fun openCustomRangePicker() {
        val dateRangePicker = com.google.android.material.datepicker.MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select Date Range")
            .setSelection(androidx.core.util.Pair(rangeStart, rangeEnd))
            .build()

        dateRangePicker.addOnPositiveButtonClickListener { selection ->
            val startDate = selection.first
            val endDate = selection.second
            if (startDate != null && endDate != null) {
                rangeStart = startDate
                rangeEnd = endDate + (23L * 60 * 60 * 1000 + 59 * 60 * 1000 + 59 * 1000)
                tvRangeLabel.text = "${chipLabelFormat.format(Date(startDate))} – ${chipLabelFormat.format(Date(endDate))}"
                if (currentTab == Tab.USAGE_ANALYTICS) {
                    loadCompanyLogs()
                }
            }
        }
        dateRangePicker.show(supportFragmentManager, "MATERIAL_DATE_RANGE_PICKER")
    }

    private fun loadCompanyLogs() {
        val rtdb = FirebaseDatabase.getInstance("https://superadmin-9a853-default-rtdb.firebaseio.com")

        db.collection("companies").get().addOnSuccessListener { compSnapshot ->
            val companiesList = mutableSetOf("All Companies")
            compSnapshot.documents.forEach { doc ->
                companiesList.add(doc.id.uppercase())
                doc.getString("companyId")?.let { companiesList.add(it.trim().uppercase()) }
            }
            availableCompanies = companiesList.toList().sorted()

            val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, availableCompanies)
            spinnerCompanyFilter.adapter = spinnerAdapter
            val spinnerIdx = availableCompanies.indexOf(selectedCompanyFilter).coerceAtLeast(0)
            spinnerCompanyFilter.setSelection(spinnerIdx)

            spinnerCompanyFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    selectedCompanyFilter = availableCompanies[position]
                    if (currentTab == Tab.USAGE_ANALYTICS) renderLogs()
                }
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }

            rtdb.getReference("activity_logs").get().addOnSuccessListener { logsSnapshot ->
                val userActionCounts = mutableMapOf<String, Int>()
                val userLastActiveMap = mutableMapOf<String, Long>()

                logsSnapshot.children.forEach { child ->
                    val ts = child.child("timestamp").getValue(Long::class.java) ?: 0L
                    val email = child.child("email").value?.toString()?.trim()?.lowercase() ?: ""
                    if (email.isNotEmpty() && ts in rangeStart..rangeEnd) {
                        userActionCounts[email] = (userActionCounts[email] ?: 0) + 1
                        val currentLast = userLastActiveMap[email] ?: 0L
                        if (ts > currentLast) userLastActiveMap[email] = ts
                    }
                }

                rtdb.getReference("user_presence").get().addOnSuccessListener { presenceSnapshot ->
                    val presenceMap = mutableMapOf<String, DataSnapshot>()
                    presenceSnapshot.children.forEach { pChild ->
                        val pEmail = pChild.child("email").value?.toString()?.trim()?.lowercase() ?: ""
                        if (pEmail.isNotEmpty()) {
                            presenceMap[pEmail] = pChild
                        }
                    }

                    db.collection("admins").get().addOnSuccessListener { adminSnapshot ->
                        val currentTime = System.currentTimeMillis()
                        val logsList = mutableListOf<UserActivityLog>()
                        val processedEmails = mutableSetOf<String>()

                        adminSnapshot.documents.forEach { doc ->
                            val email = doc.getString("email")?.trim()?.lowercase() ?: "admin@system.com"
                            processedEmails.add(email)

                            val name = doc.getString("name") ?: email.substringBefore("@").replaceFirstChar { it.uppercase() }
                            val role = doc.getString("role") ?: "COMPANY_ADMIN"
                            val companyId = doc.getString("companyId")?.trim()?.uppercase() ?: "OKARA"

                            val pSnap = presenceMap[email]
                            val isOnlineVal = pSnap?.child("isOnline")?.getValue(Boolean::class.java) == true
                            val lastSeen = pSnap?.child("lastSeen")?.getValue(Long::class.java) ?: userLastActiveMap[email] ?: 0L
                            val isOnline = isOnlineVal && (currentTime - lastSeen) < 120_000L

                            val sessionStart = pSnap?.child("sessionStart")?.getValue(Long::class.java) ?: 0L
                            val lastLogout = pSnap?.child("lastLogout")?.getValue(Long::class.java) ?: 0L
                            val dailyMins = pSnap?.child("dailyUsageMinutes")?.getValue(Long::class.java)?.toInt() ?: 0

                            val usageCount = userActionCounts[email] ?: 0

                            val loginTimeStr = if (sessionStart > 0) hourFormat.format(Date(sessionStart)) else "N/A"
                            val logoutTimeStr = if (isOnline) "Active Now" else if (lastLogout > 0) hourFormat.format(Date(lastLogout)) else if (lastSeen > 0) hourFormat.format(Date(lastSeen)) else "Offline"

                            val sessionMillis = if (sessionStart > 0) {
                                if (isOnline) (currentTime - sessionStart).coerceAtLeast(0L)
                                else if (lastLogout > sessionStart) (lastLogout - sessionStart).coerceAtLeast(0L)
                                else (lastSeen - sessionStart).coerceAtLeast(0L)
                            } else 0L

                            val sessionHours = sessionMillis / (3600 * 1000L)
                            val sessionMins = (sessionMillis / (60 * 1000L)) % 60
                            val sessionDurationStr = "${sessionHours}h ${sessionMins}m"

                            logsList.add(
                                UserActivityLog(
                                    name = name,
                                    email = email,
                                    role = role,
                                    companyId = companyId,
                                    usageCount = usageCount,
                                    lastActive = lastSeen,
                                    loginTime = loginTimeStr,
                                    logoutTime = logoutTimeStr,
                                    sessionDuration = sessionDurationStr,
                                    dailyMinutes = dailyMins,
                                    isOnline = isOnline
                                )
                            )
                        }

                        presenceMap.forEach { (pEmail, pSnap) ->
                            if (!processedEmails.contains(pEmail)) {
                                val name = pSnap.child("name").value?.toString() ?: pEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
                                val role = pSnap.child("role").value?.toString() ?: "COMPANY_ADMIN"
                                val companyId = pSnap.child("companyId").value?.toString()?.uppercase() ?: "OKARA"

                                val isOnlineVal = pSnap.child("isOnline").getValue(Boolean::class.java) == true
                                val lastSeen = pSnap.child("lastSeen").getValue(Long::class.java) ?: 0L
                                val isOnline = isOnlineVal && (currentTime - lastSeen) < 120_000L

                                val sessionStart = pSnap.child("sessionStart").getValue(Long::class.java) ?: 0L
                                val lastLogout = pSnap.child("lastLogout").getValue(Long::class.java) ?: 0L
                                val dailyMins = pSnap.child("dailyUsageMinutes").getValue(Long::class.java)?.toInt() ?: 0

                                val usageCount = userActionCounts[pEmail] ?: 0

                                val loginTimeStr = if (sessionStart > 0) hourFormat.format(Date(sessionStart)) else "N/A"
                                val logoutTimeStr = if (isOnline) "Active Now" else if (lastLogout > 0) hourFormat.format(Date(lastLogout)) else if (lastSeen > 0) hourFormat.format(Date(lastSeen)) else "Offline"

                                val sessionMillis = if (sessionStart > 0) {
                                    if (isOnline) (currentTime - sessionStart).coerceAtLeast(0L)
                                    else if (lastLogout > sessionStart) (lastLogout - sessionStart).coerceAtLeast(0L)
                                    else (lastSeen - sessionStart).coerceAtLeast(0L)
                                } else 0L

                                val sessionHours = sessionMillis / (3600 * 1000L)
                                val sessionMins = (sessionMillis / (60 * 1000L)) % 60
                                val sessionDurationStr = "${sessionHours}h ${sessionMins}m"

                                logsList.add(
                                    UserActivityLog(
                                        name = name,
                                        email = pEmail,
                                        role = role,
                                        companyId = companyId,
                                        usageCount = usageCount,
                                        lastActive = lastSeen,
                                        loginTime = loginTimeStr,
                                        logoutTime = logoutTimeStr,
                                        sessionDuration = sessionDurationStr,
                                        dailyMinutes = dailyMins,
                                        isOnline = isOnline
                                    )
                                )
                            }
                        }

                        allLogs = logsList.sortedWith(compareByDescending<UserActivityLog> { it.isOnline }.thenByDescending { it.usageCount })
                        if (currentTab == Tab.USAGE_ANALYTICS) {
                            renderLogs()
                        }
                    }.addOnFailureListener {
                        if (currentTab == Tab.USAGE_ANALYTICS) { allLogs = emptyList(); renderLogs() }
                    }
                }.addOnFailureListener {
                    if (currentTab == Tab.USAGE_ANALYTICS) { allLogs = emptyList(); renderLogs() }
                }
            }.addOnFailureListener {
                if (currentTab == Tab.USAGE_ANALYTICS) { allLogs = emptyList(); renderLogs() }
            }
        }.addOnFailureListener {
            if (currentTab == Tab.USAGE_ANALYTICS) { allLogs = emptyList(); renderLogs() }
        }
    }

    private fun renderLogs() {
        val filteredLogs = if (selectedCompanyFilter == "All Companies") {
            allLogs
        } else {
            allLogs.filter { it.companyId.equals(selectedCompanyFilter, ignoreCase = true) }
        }

        val totalUsers = filteredLogs.size
        val totalActions = filteredLogs.sumOf { it.usageCount }
        val topComp = filteredLogs.maxByOrNull { it.usageCount }?.companyId ?: "—"

        tvStatUsers.text = totalUsers.toString()
        tvStatActions.text = totalActions.toString()
        tvStatTopCompany.text = topComp

        tvEmpty.visibility = if (filteredLogs.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (filteredLogs.isEmpty()) View.GONE else View.VISIBLE

        rv.adapter = LogAdapter(filteredLogs)
        renderScoreboardGraph(filteredLogs)
    }

    private fun loadCompaniesForLicenseManagement() {
        companyRepo.getAllCompanies(object : CompanyRepository.CompanyListCallback {
            override fun onSuccess(companies: List<Company>) {
                allCompaniesList = companies
                renderLicenseManagementList(companies)
            }

            override fun onError(message: String) {
                Toast.makeText(this@CompanyLogActivity, "Error loading companies: $message", Toast.LENGTH_SHORT).show()
                allCompaniesList = emptyList()
                renderLicenseManagementList(emptyList())
            }
        })
    }

    private fun renderLicenseManagementList(companies: List<Company>) {
        val total = companies.size
        val currentTime = System.currentTimeMillis()
        val active = companies.count { it.licenseExpiry == 0L || it.licenseExpiry > currentTime }
        val expiredOrSoon = total - active

        tvStatUsers.text = total.toString()
        tvStatActions.text = active.toString()
        tvStatTopCompany.text = expiredOrSoon.toString()

        tvEmpty.visibility = if (companies.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (companies.isEmpty()) View.GONE else View.VISIBLE
        rv.adapter = LicenseManagementAdapter(companies)
    }

    private fun extendLicense30Days(company: Company) {
        val baseTime = if (company.licenseExpiry > System.currentTimeMillis()) company.licenseExpiry else System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            timeInMillis = baseTime
            add(Calendar.DAY_OF_YEAR, 30)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val newExpiry = cal.timeInMillis
        val updatedCompany = company.copy(licenseStatus = "ACTIVE", licenseExpiry = newExpiry)

        companyRepo.updateCompany(updatedCompany, object : CompanyRepository.SimpleCallback {
            override fun onSuccess() {
                Toast.makeText(this@CompanyLogActivity, "License extended by 30 days (Expires 12:00 PM noon) for ${company.companyName}", Toast.LENGTH_LONG).show()
                loadCompaniesForLicenseManagement()
            }

            override fun onError(message: String) {
                Toast.makeText(this@CompanyLogActivity, "Failed to update license: $message", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun renderScoreboardGraph(logs: List<UserActivityLog>) {
        scoreboardGraphContainer.removeAllViews()

        val companyUsage = logs.groupBy { it.companyId }.mapValues { entry -> entry.value.sumOf { it.usageCount } }
            .entries.sortedByDescending { it.value }

        val maxActions = companyUsage.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1

        companyUsage.forEach { (comp, actions) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }

            val label = TextView(this).apply {
                text = comp
                textSize = 12f
                setTextColor(Color.parseColor("#0D2E5C"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(120, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val barLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, 16, 1f)
                background = androidx.core.content.ContextCompat.getDrawable(this@CompanyLogActivity, android.R.drawable.screen_background_dark_transparent)
            }

            val percent = (actions * 100) / maxActions
            val fillBar = View(this).apply {
                setBackgroundColor(Color.parseColor("#1976D2"))
                layoutParams = LinearLayout.LayoutParams(0, 16, percent.toFloat().coerceAtLeast(1f))
            }

            barLayout.addView(fillBar)

            val countText = TextView(this).apply {
                text = "$actions acts"
                textSize = 11f
                setTextColor(Color.parseColor("#555555"))
                setPadding(12, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            row.addView(label)
            row.addView(barLayout)
            row.addView(countText)

            scoreboardGraphContainer.addView(row)
        }
    }

    private inner class LogAdapter(
        private val items: List<UserActivityLog>
    ) : RecyclerView.Adapter<LogAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvLogAdminName)
            val role: TextView = view.findViewById(R.id.tvLogRoleBadge)
            val company: TextView = view.findViewById(R.id.tvLogCompany)
            val usageCount: TextView = view.findViewById(R.id.tvLogUsageCount)
            val lastActive: TextView = view.findViewById(R.id.tvLogLastActive)
            val heartbeat: TextView = view.findViewById(R.id.tvLogHeartbeat)
            val loginLogoutInfo: TextView = view.findViewById(R.id.tvLoginLogoutInfo)
            val dailyUsage: TextView = view.findViewById(R.id.tvDailyUsageMinutes)
            val progress: ProgressBar = view.findViewById(R.id.progressUsage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_company_log, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.role.text = item.role.replace("_", " ")
            holder.company.text = "Company: ${item.companyId}  ·  ${item.email}"
            holder.usageCount.text = "Usage: ${item.usageCount} actions"
            holder.lastActive.text = "Last Active: ${timeFormat.format(item.lastActive)}"

            holder.heartbeat.text = if (item.isOnline) "● Online" else "○ Offline"
            holder.heartbeat.setTextColor(Color.parseColor(if (item.isOnline) "#2E7D32" else "#757575"))

            holder.loginLogoutInfo.text = "Login: ${item.loginTime}  ·  Logout: ${item.logoutTime}  ·  Session: ${item.sessionDuration}"
            holder.dailyUsage.text = "Daily Usage: ${item.dailyMinutes} mins today"

            val maxCount = items.maxOfOrNull { it.usageCount }?.coerceAtLeast(1) ?: 1
            val percent = (item.usageCount * 100) / maxCount
            holder.progress.progress = percent
        }
    }

    private inner class LicenseManagementAdapter(
        private val items: List<Company>
    ) : RecyclerView.Adapter<LicenseManagementAdapter.LicenseVH>() {

        inner class LicenseVH(view: View) : RecyclerView.ViewHolder(view) {
            val tvCompanyName: TextView = view.findViewById(R.id.tvLogAdminName)
            val tvStatusBadge: TextView = view.findViewById(R.id.tvLogRoleBadge)
            val tvCompanyDetails: TextView = view.findViewById(R.id.tvLogCompany)
            val tvExpiryInfo: TextView = view.findViewById(R.id.tvLoginLogoutInfo)
            val tvNotificationStatus: TextView = view.findViewById(R.id.tvDailyUsageMinutes)
            val tvActionHint: TextView = view.findViewById(R.id.tvLogUsageCount)
            val btnRenew: Button = view.findViewById(R.id.btnRenewLicense)
            val progressBar: ProgressBar = view.findViewById(R.id.progressUsage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LicenseVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_company_log, parent, false)
            return LicenseVH(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: LicenseVH, position: Int) {
            val company = items[position]
            val currentTime = System.currentTimeMillis()
            val isExpired = company.licenseExpiry > 0 && currentTime > company.licenseExpiry
            val effectiveStatus = if (isExpired) "EXPIRED" else (company.licenseStatus.ifEmpty { "ACTIVE" })

            holder.tvCompanyName.text = company.companyName
            holder.tvStatusBadge.text = effectiveStatus
            holder.tvStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (effectiveStatus == "ACTIVE") "#2E7D32" else if (effectiveStatus == "DEMO") "#1976D2" else "#C62828")
            )

            holder.tvCompanyDetails.text = "ID: ${company.companyId} · City: ${company.city} · Owner: ${company.ownerName}"

            val createdAtFormatted = if (company.createdAt > 0) fullDateFormat.format(Date(company.createdAt)) else "N/A"
            val expiryFormatted = if (company.licenseExpiry > 0) fullDateFormat.format(Date(company.licenseExpiry)) else "No Expiry Set"
            holder.tvExpiryInfo.text = "Created: $createdAtFormatted\nExpiry (12:00 PM Noon): $expiryFormatted"

            holder.tvNotificationStatus.text = "Reminders & Notifications: Sent to Dealers, Supervisors & Managers"
            holder.tvActionHint.text = if (isExpired) "Status: Expired & Access Blocked (Permanent Log)" else "Status: Active & Fully Functional"
            holder.tvActionHint.setTextColor(Color.parseColor(if (isExpired) "#C62828" else "#2E7D32"))

            holder.btnRenew.visibility = View.VISIBLE
            holder.btnRenew.text = "Renew (+30 Days)"
            holder.btnRenew.setOnClickListener {
                extendLicense30Days(company)
            }

            holder.progressBar.progress = if (isExpired) 0 else 100
        }
    }
}
