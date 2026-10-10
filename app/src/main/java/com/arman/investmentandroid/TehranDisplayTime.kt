package com.arman.investmentandroid

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Only a view formatter; timestamps and financial records remain canonical epoch millis. */
object TehranDisplayTime {
    fun gregorian(timestampMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Tehran")
        }.format(Date(timestampMs))
}
