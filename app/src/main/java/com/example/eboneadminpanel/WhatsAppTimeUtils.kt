package com.example.superadmin

import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object WhatsAppTimeUtils {

    /**
     * Formats the timestamp for WhatsApp-style seen time:
     * - Today: "02:45 PM"
     * - Yesterday: "Yesterday 02:45 PM"
     * - Older: "25/03/2025 02:45 PM"
     */
    fun getWhatsAppBlueTickTime(timestamp: Long): String {
        val validTimestamp = if (timestamp > 0L) timestamp else System.currentTimeMillis()

        val date = Date(validTimestamp)
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("dd/MM/yyyy h:mm a", Locale.getDefault())

        val nowCalendar = Calendar.getInstance()
        val targetCalendar = Calendar.getInstance().apply { time = date }

        return when {
            DateUtils.isToday(validTimestamp) -> {
                timeFormat.format(date)
            }
            isYesterday(nowCalendar, targetCalendar) -> {
                "Yesterday ${timeFormat.format(date)}"
            }
            else -> {
                dateFormat.format(date)
            }
        }
    }

    private fun isYesterday(now: Calendar, target: Calendar): Boolean {
        val clone = now.clone() as Calendar
        clone.add(Calendar.DAY_OF_YEAR, -1)
        return clone.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
               clone.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    }
}
