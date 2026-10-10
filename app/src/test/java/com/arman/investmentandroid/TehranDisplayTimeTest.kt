package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class TehranDisplayTimeTest {
    @Test fun tehranCrossesMidnightWithoutChangingLedgerTime() {
        val epoch = Instant.parse("2026-10-09T20:30:00Z").toEpochMilli()
        assertEquals("2026-10-10 00:00", TehranDisplayTime.gregorian(epoch))
    }

    @Test fun deviceTimezoneCannotChangePortfolioHistoryLabels() {
        val original = TimeZone.getDefault()
        val epoch = Instant.parse("2026-10-10T10:00:00Z").toEpochMilli()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            val paris = TehranDisplayTime.gregorian(epoch)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val la = TehranDisplayTime.gregorian(epoch)
            assertEquals("2026-10-10 13:30", paris)
            assertEquals(paris, la)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
