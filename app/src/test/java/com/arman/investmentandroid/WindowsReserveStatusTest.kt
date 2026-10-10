package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WindowsReserveStatusTest {
    @Test fun exactReserveHasNoTradeSuggestion() {
        val result = WindowsReserveStatus.fromVerifiedCash(2_000_000.0, 2_000_000.0)
        assertEquals(WindowsReserveStatus.Kind.EXACT, result.kind)
        assertEquals(0.0, result.differenceToman, 0.0)
    }

    @Test fun reserveSurplusAndShortfallUseAbsoluteToman() {
        val surplus = WindowsReserveStatus.fromVerifiedCash(8_000_000.0, 5_000_000.0)
        val shortfall = WindowsReserveStatus.fromVerifiedCash(1_000_000.0, 5_000_000.0)
        assertEquals(WindowsReserveStatus.Kind.SURPLUS, surplus.kind)
        assertEquals(3_000_000.0, surplus.differenceToman, 0.0)
        assertEquals(WindowsReserveStatus.Kind.SHORTFALL, shortfall.kind)
        assertEquals(-4_000_000.0, shortfall.differenceToman, 0.0)
    }

    @Test fun invalidInputsAndOverflowFailClosed() {
        for (input in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                WindowsReserveStatus.fromVerifiedCash(input, 1.0)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WindowsReserveStatus.fromVerifiedCash(1.0, -1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WindowsReserveStatus.fromVerifiedCash(Double.MAX_VALUE, 0.0 - Double.MAX_VALUE)
        }
    }

    @Test fun zeroReserveIsAValidGoal() {
        val result = WindowsReserveStatus.fromVerifiedCash(2_000.0, 0.0)
        assertEquals(WindowsReserveStatus.Kind.SURPLUS, result.kind)
        assertEquals(0.0, result.targetToman, 0.0)
    }
}
