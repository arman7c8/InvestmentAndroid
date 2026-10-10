package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EditableFinancialNumberTest {
    @Test fun fractionalCryptoQuantityIsNotTruncated() {
        val initial = 0.123456789123456
        assertEquals(initial, EditableFinancialNumber.format(initial).toDouble(), 0.0)
    }
    @Test fun fractionalQuoteAndAverageCostRoundTripExactly() {
        for (value in listOf(15000.125, 11500.75, 1.0e-8, 0.0, 9999999999.25)) {
            assertEquals(value, EditableFinancialNumber.format(value).toDouble(), 0.0)
        }
    }
    @Test fun plainNumbersHaveNoThousandsSeparatorOrUnnecessaryDecimal() {
        assertEquals("15000", EditableFinancialNumber.format(15000.0))
        assertEquals("0.00000001", EditableFinancialNumber.format(0.00000001))
    }
    @Test fun invalidNumberRejectedBeforeFinancialEditor() {
        assertThrows(IllegalArgumentException::class.java) {
            EditableFinancialNumber.format(Double.POSITIVE_INFINITY)
        }
    }
}
