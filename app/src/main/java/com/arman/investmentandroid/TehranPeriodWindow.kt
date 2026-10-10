package com.arman.investmentandroid

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Calendar boundaries for the UI only. Canonical ledger timestamps remain
 * epoch milliseconds and are never rewritten for timezone or Jalali display.
 * Mirrors the Windows v0.13 Tehran-local semantic period convention.
 */
object TehranPeriodWindow {
    val ZONE: ZoneId = ZoneId.of("Asia/Tehran")

    fun startMillis(period: String, nowMillis: Long): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(ZONE)
        val today = now.toLocalDate()
        val startDate: LocalDate = when (period) {
            "Day" -> today
            "Week" -> today.minusDays(6)
            "Month" -> today.withDayOfMonth(1)
            "Year" -> today.withDayOfYear(1)
            else -> throw IllegalArgumentException("Unsupported summary period.")
        }
        val start: ZonedDateTime = startDate.atStartOfDay(ZONE)
        return start.toInstant().toEpochMilli()
    }
}
