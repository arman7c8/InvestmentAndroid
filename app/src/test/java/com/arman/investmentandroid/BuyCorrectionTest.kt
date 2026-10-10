package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BuyCorrectionTest {
    @Test fun initialUserBuyIsUnchanged() {
        val result = BuyCorrection.calculate(2.0, 8000.0, 2.0, 15000.0)
        assertEquals(4.0, result.quantity, 0.0)
        assertEquals(11500.0, result.averageCost, 0.0)
    }
    @Test fun revisedBuyRecomputesCostBasisWithoutInflatingQuantity() {
        val result = BuyCorrection.calculate(2.0, 8000.0, 1.0, 12000.0)
        assertEquals(3.0, result.quantity, 0.0)
        assertEquals(28000.0 / 3.0, result.averageCost, 0.000001)
    }
    @Test fun invalidCorrectionsFailClosed() {
        for (amount in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                BuyCorrection.calculate(2.0, 8000.0, amount, 15000.0)
            }
        }
    }
    @Test fun overflowFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            BuyCorrection.calculate(Double.MAX_VALUE, Double.MAX_VALUE, 2.0, 2.0)
        }
    }
}
