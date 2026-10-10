package com.arman.investmentandroid

import java.math.BigDecimal

/** Exact round-trip text for editable fields; never use a rounded display label. */
object EditableFinancialNumber {
    fun format(value: Double): String {
        require(value.isFinite()) { "Non-finite financial input." }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}
