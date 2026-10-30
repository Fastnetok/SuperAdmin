package com.example.superadmin

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.superadmin.core.CompanyDataHelper
import com.google.android.gms.tasks.Tasks
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DealerPaymentLogActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val dealerNameCache = mutableMapOf<String, String>()

    private lateinit var listContainer: LinearLayout
    private lateinit var totalText: TextView
    private lateinit var filterLabel: TextView
    private lateinit var dealerFilterButton: TextView

    private var rangeStart: Long = 0L
    private var rangeEnd: Long = Long.MAX_VALUE
    private var selectedDealerId: String? = null // null = All Dealers
    private var selectedDealerName: String = "All Dealers"

    private val textDark = Color.parseColor("#172033")
    private val textMuted = Color.parseColor("#667085")
    private val borderLight = Color.parseColor("#E4E7EC")
    private val navyMid = Color.parseColor("#1D4ED8")
    private val green = Color.parseColor("#12B76A")
    private val orange = Color.parseColor("#F79009")
    private val cardWhite = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        setTodayRange()
        loadLog()
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
        headerRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@DealerPaymentLogActivity).apply {
                text = "Dealer Payment History & Ledger"
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textDark)
            })
            addView(TextView(this@DealerPaymentLogActivity).apply {
                text = "Manual Top-up & SMS OCR Verified Audit Trail"
                textSize = 11f
                setTextColor(textMuted)
            })
        })
        root.addView(headerRow)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(16)) })

        // Quick Filters row 1: Today, Yesterday, 7 Days
        val filterRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterRow1.addView(filterChip("Today") { setTodayRange(); loadLog() })
        filterRow1.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        filterRow1.addView(filterChip("Yesterday") { setYesterdayRange(); loadLog() })
        filterRow1.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        filterRow1.addView(filterChip("7 Days") { setLast7DaysRange(); loadLog() })
        root.addView(filterRow1)

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(8)) })

        // Quick Filters row 2: This Month, Last Month, Custom
        val filterRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterRow2.addView(filterChip("This Month") { setThisMonthRange(); loadLog() })
        filterRow2.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        filterRow2.addView(filterChip("Last Month") { setLastMonthRange(); loadLog() })
        filterRow2.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        filterRow2.addView(filterChip("Custom") { pickCustomRange() })
        root.addView(filterRow2)

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(8)) })

        // Dealer Filter Button
        dealerFilterButton = TextView(this).apply {
            text = "Dealer: All Dealers ▾"
            textSize = 13f
            setTextColor(textDark)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), borderLight)
            }
            setOnClickListener { showDealerPickerDialog() }
        }
        root.addView(dealerFilterButton)

        filterLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(textMuted)
            setPadding(dp(2), dp(10), 0, dp(4))
        }
        root.addView(filterLabel)

        totalText = TextView(this).apply {
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(navyMid)
            setPadding(dp(2), 0, 0, dp(16))
        }
        root.addView(totalText)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        return scroll
    }

    private fun filterChip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(navyMid)
        gravity = Gravity.CENTER
        setPadding(dp(2), dp(8), dp(2), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), borderLight)
        }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        setOnClickListener { onClick() }
    }

    private fun showDealerPickerDialog() {
        db.collection("dealers").get().addOnSuccessListener { snapshot ->
            val companyDealers = snapshot.documents.filter { doc ->
                CompanyDataHelper.belongsToCurrentCompany(this, doc)
            }

            val names = mutableListOf("All Dealers")
            val ids = mutableListOf<String?>(null)

            for (d in companyDealers) {
                val dName = d.getString("name") ?: d.id
                names.add(dName)
                ids.add(d.id)
            }

            AlertDialog.Builder(this)
                .setTitle("Filter by Dealer")
                .setItems(names.toTypedArray()) { _, which ->
                    selectedDealerId = ids[which]
                    selectedDealerName = names[which]
                    dealerFilterButton.text = "Dealer: $selectedDealerName ▾"
                    loadLog()
                }
                .show()
        }
    }

    private fun setTodayRange() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        filterLabel.text = "Today (${formatDate(rangeStart)})"
    }

    private fun setYesterdayRange() {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        rangeStart = cal.timeInMillis
        val endCal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }
        rangeEnd = endCal.timeInMillis
        filterLabel.text = "Yesterday (${formatDate(rangeStart)})"
    }

    private fun setLast7DaysRange() {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -7)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        filterLabel.text = "Last 7 Days (${formatDate(rangeStart)} — today)"
    }

    private fun setThisMonthRange() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        filterLabel.text = "This Month (${formatDate(rangeStart)} — today)"
    }

    private fun setLastMonthRange() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.add(Calendar.MONTH, -1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        cal.add(Calendar.MILLISECOND, -1)
        rangeEnd = cal.timeInMillis
        filterLabel.text = "Last Month (${formatDate(rangeStart)} — ${formatDate(rangeEnd)})"
    }

    private fun pickCustomRange() {
        val dateRangePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select Date Range")
            .setSelection(
                androidx.core.util.Pair(
                    MaterialDatePicker.thisMonthInUtcMilliseconds(),
                    MaterialDatePicker.todayInUtcMilliseconds()
                )
            )
            .build()

        dateRangePicker.addOnPositiveButtonClickListener { selection ->
            val startDate = selection.first ?: 0L
            val endDate = selection.second ?: 0L
            rangeStart = startDate
            rangeEnd = endDate + (24 * 60 * 60 * 1000L) - 1L
            filterLabel.text = "Custom (${formatDate(rangeStart)} — ${formatDate(rangeEnd)})"
            loadLog()
        }

        dateRangePicker.show(supportFragmentManager, "DATE_RANGE_PICKER")
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()).format(Date(millis))

    private fun loadLog() {
        listContainer.removeAllViews()
        totalText.text = "Loading…"

        val tasks = listOf(
            db.collection("dealerPayments").get(),
            db.collection("dealerTransactions").get()
        )

        Tasks.whenAllComplete(tasks).addOnSuccessListener { results ->
            val allDocs = mutableListOf<DocumentSnapshot>()
            for (task in results) {
                if (task.isSuccessful) {
                    val snap = task.result as? com.google.firebase.firestore.QuerySnapshot
                    if (snap != null) {
                        allDocs.addAll(snap.documents)
                    }
                }
            }

            val filteredDocs = allDocs.filter { doc ->
                val isCompanyMatch = CompanyDataHelper.belongsToCurrentCompany(this, doc)
                val time = doc.getLong("submittedAt") ?: doc.getLong("timestamp") ?: doc.getLong("createdAt") ?: 0L
                val isInRange = time in rangeStart..rangeEnd
                val docDealerId = doc.getString("dealerId") ?: ""
                val isDealerMatch = selectedDealerId == null || docDealerId == selectedDealerId

                isCompanyMatch && isInRange && isDealerMatch
            }.sortedByDescending { doc ->
                doc.getLong("submittedAt") ?: doc.getLong("timestamp") ?: doc.getLong("createdAt") ?: 0L
            }

            listContainer.removeAllViews()
            if (filteredDocs.isEmpty()) {
                totalText.text = "No payments in this range"
                listContainer.addView(TextView(this).apply {
                    text = "Nothing submitted yet for this period."
                    setTextColor(textMuted)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16), 0, dp(16))
                })
                return@addOnSuccessListener
            }

            var total = 0.0
            var verifiedCount = 0
            for (doc in filteredDocs) {
                val amount = doc.getDouble("amount") ?: 0.0
                total += amount
                val status = doc.getString("status") ?: "COMPLETED"
                if (status == "VERIFIED" || status == "COMPLETED" || doc.contains("dealerBalanceAfter")) verifiedCount++
                renderRowWithDealerName(doc)
            }
            totalText.text = "Rs. ${"%,.0f".format(total)} total  •  ${filteredDocs.size} payments  •  $verifiedCount verified"
        }.addOnFailureListener { e ->
            totalText.text = "Failed to load: ${e.message}"
        }
    }

    private fun renderRowWithDealerName(doc: DocumentSnapshot) {
        val dealerId = doc.getString("dealerId") ?: ""
        if (dealerId.isEmpty()) {
            listContainer.addView(logRow(doc, "Unknown dealer", "Okara"))
            return
        }
        val cached = dealerNameCache[dealerId]
        if (cached != null) {
            db.collection("dealers").document(dealerId).get().addOnSuccessListener { dDoc ->
                val zone = dDoc.getString("zone")?.ifBlank { null } ?: "Okara"
                listContainer.addView(logRow(doc, cached, zone))
            }.addOnFailureListener {
                listContainer.addView(logRow(doc, cached, "Okara"))
            }
            return
        }
        db.collection("dealers").document(dealerId).get()
            .addOnSuccessListener { dealerDoc ->
                val name = dealerDoc.getString("name") ?: dealerId
                val zone = dealerDoc.getString("zone")?.ifBlank { null } ?: "Okara"
                dealerNameCache[dealerId] = name
                listContainer.addView(logRow(doc, name, zone))
            }
            .addOnFailureListener {
                listContainer.addView(logRow(doc, dealerId, "Okara"))
            }
    }

    private fun logRow(doc: DocumentSnapshot, dealerName: String, zone: String): LinearLayout {
        val panel = doc.getString("panel") ?: "?"
        val amount = doc.getDouble("amount") ?: 0.0
        val isManualTopup = doc.contains("dealerBalanceAfter") || doc.contains("ispDealerId") || doc.getString("resultText") != null
        val tid = doc.getString("bankTransactionId") ?: doc.getString("transactionId") ?: ""

        val subtitle = if (isManualTopup) {
            val balanceAfter = doc.getString("dealerBalanceAfter") ?: ""
            if (balanceAfter.isNotBlank()) "$panel Panel  •  Manual Dealer Top-up (New Balance: $balanceAfter)" else "$panel Panel  •  Manual Dealer Top-up"
        } else {
            if (tid.isNotBlank()) "$panel Panel  •  TID: $tid" else "$panel Panel  •  Online SMS Deposit"
        }

        val submittedAt = doc.getLong("submittedAt") ?: doc.getLong("timestamp") ?: System.currentTimeMillis()
        val verifiedAt = doc.getLong("verifiedAt")
        val status = doc.getString("status") ?: "COMPLETED"
        val transferStatus = doc.getString("transferStatus") ?: "TRANSFERRED"
        val matchedSmsBody = doc.getString("matchedSmsBody") ?: doc.getString("note") ?: ""

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(cardWhite)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), borderLight)
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }

        val topRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPaymentLogActivity).apply {
                text = "$dealerName ($zone)"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textDark)
            })
            addView(TextView(this@DealerPaymentLogActivity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(textMuted)
            })
        })
        topRow.addView(TextView(this).apply {
            text = "Rs. ${"%,.0f".format(amount)}"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(navyMid)
        })
        card.addView(topRow)

        if (!isManualTopup && matchedSmsBody.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = matchedSmsBody.take(160)
                textSize = 11f
                setTextColor(Color.parseColor("#344054"))
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F8F9FC"))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), borderLight)
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6); it.bottomMargin = dp(6) }
            })
        }

        card.addView(View(this).apply {
            setBackgroundColor(borderLight)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).also { it.topMargin = dp(4); it.bottomMargin = dp(8) }
        })

        if (isManualTopup) {
            card.addView(stageLine("Type", "Manual Dealer Top-up", navyMid))
            card.addView(stageLine("Processed At", formatTime(submittedAt), textMuted))
            card.addView(stageLine("Panel Status", "Successful", green))
        } else {
            card.addView(stageLine("Type", "SMS / Online Deposit", green))
            card.addView(stageLine("Submitted", formatTime(submittedAt), textMuted))
            card.addView(
                if (verifiedAt != null || status == "COMPLETED" || status == "VERIFIED")
                    stageLine("Verification", "Verified & Processed", green)
                else
                    stageLine("Verification", "Pending", orange)
            )
            card.addView(
                if (transferStatus == "TRANSFERRED" || status == "COMPLETED" || status == "VERIFIED")
                    stageLine("Transfer", "Completed", green)
                else
                    stageLine("Transfer", "Pending", orange)
            )
        }

        return card
    }

    private fun stageLine(label: String, value: String, valueColor: Int): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            addView(TextView(this@DealerPaymentLogActivity).apply {
                text = label
                textSize = 11f
                setTextColor(textMuted)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(TextView(this@DealerPaymentLogActivity).apply {
                text = value
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(valueColor)
            })
        }
}
