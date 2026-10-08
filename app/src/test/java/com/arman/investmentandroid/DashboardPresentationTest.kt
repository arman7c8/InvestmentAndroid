package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPresentationTest {
    @Test fun emptyPortfolioSkipsEmptySections() {
        assertFalse(DashboardPresentation.hasAssets(0))
        assertFalse(DashboardPresentation.hasAssets(-1))
        assertTrue(DashboardPresentation.hasAssets(1))
    }
    @Test fun periodHeadingsFollowNaturalPersianGrammar() {
        assertEquals("خلاصه ماه", DashboardPresentation.summaryHeading("Month", "fa"))
        assertEquals("خلاصه هفته", DashboardPresentation.summaryHeading("Week", "fa"))
        assertEquals("خلاصه روز", DashboardPresentation.summaryHeading("Day", "fa"))
        assertEquals("خلاصه سال", DashboardPresentation.summaryHeading("Year", "fa"))
        assertEquals("Month Summary", DashboardPresentation.summaryHeading("Month", "en"))
    }
    @Test fun persianDashboardHeadingIsMoreCompact() {
        assertEquals(22f, DashboardPresentation.titleSizeSp(true))
        assertTrue(DashboardPresentation.titleSizeSp(false) > DashboardPresentation.titleSizeSp(true))
    }
}
