package com.arman.investmentandroid

import java.math.BigDecimal

/**
 * Read-only Windows Core policy presentation. Cash reserve is an absolute Toman
 * goal and NEVER a holding, transaction, rebalance percentage or cash movement.
 * Inputs must already originate from a checksum-verified Windows Core preview.
 */
object WindowsReserveStatus {
    enum class Kind { SURPLUS, SHORTFALL, EXACT }

    data class Result(
        val targetToman: Double,
        val actualToman: Double,
        val differenceToman: Double,
        val kind: Kind
    )

    fun fromVerifiedCash(actualToman: Double, targetToman: Double): Result {
        require(actualToman.isFinite() && targetToman.isFinite() &&
            targetToman >= 0.0) { "Invalid verified Windows Core cash reserve." }

        val delta = BigDecimal.valueOf(actualToman)
            .subtract(BigDecimal.valueOf(targetToman))
        val amount = delta.toDouble()
        require(amount.isFinite()) { "Windows Core reserve gap exceeds supported precision." }
        return Result(
            targetToman = targetToman,
            actualToman = actualToman,
            differenceToman = amount,
            kind = when (delta.signum()) {
                1 -> Kind.SURPLUS
                -1 -> Kind.SHORTFALL
                else -> Kind.EXACT
            }
        )
    }
}
