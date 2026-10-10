package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TehranPeriodWindowTest {
    private fun ms(value: String) = Instant.parse(value).toEpochMilli()
    private fun local(date: String) = LocalDate.parse(date).atStartOfDay(
        ZoneId.of("Asia/Tehran")
    ).toInstant().toEpochMilli()

    @Test fun todayUsesTehranMidnightNotDeviceTimezone() {
        // 2026-10-10 00:30 Tehran equals 2026-10-09 21:00Z
        assertEquals(local("2026-10-10"),
            TehranPeriodWindow.startMillis("Day", ms("2026-10-09T21:00:00Z")))
        assertEquals(local("2026-10-09"),
            TehranPeriodWindow.startMillis("Day", ms("2026-10-09T19:00:00Z")))
    }

    @Test fun lastSevenDaysUsesFullTehranCalendarDates() {
        assertEquals(local("2026-10-04"),
            TehranPeriodWindow.startMillis("Week", ms("2026-10-10T12:00:00Z")))
    }

    @Test fun monthAndYearHaveCalendarBoundaries() {
        assertEquals(local("2026-02-01"),
            TehranPeriodWindow.startMillis("Month", ms("2026-02-28T20:00:00Z")))
        assertEquals(local("2026-01-01"),
            TehranPeriodWindow.startMillis("Year", ms("2026-10-10T12:00:00Z")))
    }

    @Test fun rejectedPeriodCannotUseUnintendedWindow() {
        assertThrows(IllegalArgumentException::class.java) {
            TehranPeriodWindow.startMillis("Unknown", ms("2026-10-10T12:00:00Z"))
        }
    }
}
