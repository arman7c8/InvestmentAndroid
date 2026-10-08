package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class StartupAppearanceTest {
    @Test fun startsOnDashboardWithoutWelcomeStep() {
        assertEquals(StartupScreen.Destination.PORTFOLIO,
            StartupScreen.destination(false, false))
    }
    @Test fun pinRequirementCannotBeSkipped() {
        assertEquals(StartupScreen.Destination.LOCKED,
            StartupScreen.destination(true, false))
        assertEquals(StartupScreen.Destination.LOCKED,
            StartupScreen.destination(true, true))
    }
    @Test fun restoresPriceCenterIfItWasOpen() {
        assertEquals(StartupScreen.Destination.PRICE_CENTER,
            StartupScreen.destination(false, true))
    }
    private fun lightness(color: Int): Double {
        fun component(shift: Int): Double {
            val c = ((color ushr shift) and 255) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * component(16) + 0.7152 * component(8) + 0.0722 * component(0)
    }
    private fun contrast(a: Int, b: Int): Double {
        val l1 = lightness(a)
        val l2 = lightness(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
    @Test fun importantTextAndProfitColorsMeetContrastRequirement() {
        for (surface in listOf(PortfolioAppearance.BACKGROUND, PortfolioAppearance.SURFACE)) {
            for (ink in listOf(PortfolioAppearance.TEXT_PRIMARY,
                PortfolioAppearance.TEXT_SECONDARY, PortfolioAppearance.SUCCESS,
                PortfolioAppearance.ERROR, PortfolioAppearance.WARNING)) {
                assertTrue("Low contrast for " + ink + " on " + surface,
                    contrast(ink, surface) >= 4.5)
            }
        }
    }
    @Test fun heroAndCardAreNotFlatWhite() {
        assertTrue(contrast(PortfolioAppearance.BACKGROUND, PortfolioAppearance.SURFACE) > 1.1)
        assertTrue(contrast(PortfolioAppearance.HERO_START, PortfolioAppearance.SURFACE) > 1.05)
    }
}
