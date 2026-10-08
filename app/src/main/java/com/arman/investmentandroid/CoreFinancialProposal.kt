package com.arman.investmentandroid

import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/**
 * A proposal is NOT a ledger entry or a shared-portfolio edit. It contains
 * only a single explicit operation against a verified, read-only Windows Core
 * v2 snapshot. A future server must recheck the baseline before applying it.
 */
object CoreFinancialProposal {
    const val FORMAT = "investment.core.financial.proposal"
    const val VERSION = 1
    private const val CASH_LIMIT = 1e15
    private const val QUANTITY_LIMIT = 1e12

    data class Command(
        val type: String,
        val amountToman: Double,
        val assetId: String? = null,
        val accountId: String? = null,
        val quantity: Double? = null,
        val destinationAccountId: String? = null
    )

    private fun requireNumber(value: Double?, name: String, max: Double, positive: Boolean = false): Double {
        require(value != null && value.isFinite() && abs(value) <= max &&
            (!positive || value > 0.0)) { "Invalid $name." }
        return value
    }

    private fun requireAccount(
        id: String?,
        balances: Map<String, Double>
    ): Pair<String, Double> {
        require(!id.isNullOrBlank() && id == id.trim() && id.length <= 128 &&
            id != "external" && id in balances) { "Unknown Windows Core cash account." }
        return id to requireNumber(balances.getValue(id), "Core cash balance", CASH_LIMIT)
    }

    /** Guard double-precision no-ops and balances crossing zero. */
    private fun changedValue(before: Double, delta: Double, limit: Double, label: String): Double {
        val after = before + delta
        require(before.isFinite() && before >= 0.0 && before <= limit &&
            after.isFinite() && after >= 0.0 && after <= limit && after != before) {
            "Unsafe or numerically invisible $label change."
        }
        return after
    }

    fun create(
        snapshot: CoreSnapshotPreview.Summary,
        command: Command,
        operationId: String = UUID.randomUUID().toString()
    ): String {
        require(snapshot.policy != null && Regex("[0-9a-f]{64}").matches(snapshot.sha)) {
            "A verified Windows Core v2 snapshot is required."
        }
        require(UUID.fromString(operationId).toString() == operationId.lowercase()) {
            "Invalid proposal operation ID."
        }
        val cash = snapshot.cashBalances.associate { it.id to it.balanceToman }
        val positions = snapshot.positions.associate { it.id to it.quantity }
        require(cash.size == snapshot.cashBalances.size &&
            positions.size == snapshot.positions.size) { "Duplicate Windows Core identity." }
        val amount = requireNumber(command.amountToman, "amount Toman", CASH_LIMIT, positive = true)
        val type = command.type
        val data = JSONObject().put("type", type).put("amountToman", amount)
        when (type) {
            "buy", "sell" -> {
                require(command.destinationAccountId == null) {
                    "Trades cannot specify another destination account."
                }
                val (account, balance) = requireAccount(command.accountId, cash)
                val assetId = command.assetId
                require(!assetId.isNullOrBlank() && assetId == assetId.trim() &&
                    assetId.length <= 128 && assetId != "external" && assetId in positions) {
                    "Unknown Windows Core asset."
                }
                val oldQuantity = requireNumber(
                    positions.getValue(assetId), "Core quantity", QUANTITY_LIMIT
                )
                val quantity = requireNumber(
                    command.quantity, "trade quantity", QUANTITY_LIMIT, positive = true
                )
                if (type == "buy") {
                    require(balance >= amount) { "Insufficient cash for purchase." }
                    changedValue(balance, -amount, CASH_LIMIT, "cash")
                    changedValue(oldQuantity, quantity, QUANTITY_LIMIT, "asset quantity")
                } else {
                    require(oldQuantity >= quantity) {
                        "Insufficient asset quantity for sale."
                    }
                    changedValue(balance, amount, CASH_LIMIT, "cash")
                    changedValue(oldQuantity, -quantity, QUANTITY_LIMIT, "asset quantity")
                }
                require((amount / quantity).isFinite() && amount / quantity > 0.0) {
                    "Trade execution price exceeds numeric precision."
                }
                data.put("assetId", assetId).put("accountId", account)
                    .put("quantity", quantity)
                    .put("expectedAccountBalanceToman", balance)
                    .put("expectedAssetQuantity", oldQuantity)
            }
            "transfer" -> {
                require(command.assetId == null && command.quantity == null) {
                    "Cash transfer cannot include an asset."
                }
                val (from, fromBalance) = requireAccount(command.accountId, cash)
                val (to, toBalance) = requireAccount(command.destinationAccountId, cash)
                require(from != to) { "Transfer requires two different accounts." }
                require(fromBalance >= amount) { "Insufficient transfer cash." }
                changedValue(fromBalance, -amount, CASH_LIMIT, "source cash")
                changedValue(toBalance, amount, CASH_LIMIT, "destination cash")
                data.put("sourceAccountId", from).put("destinationAccountId", to)
                    .put("expectedSourceBalanceToman", fromBalance)
                    .put("expectedDestinationBalanceToman", toBalance)
            }
            "deposit", "withdraw" -> {
                require(command.assetId == null && command.quantity == null &&
                    command.destinationAccountId == null) {
                    "External cash movement cannot include asset or second account."
                }
                val (account, balance) = requireAccount(command.accountId, cash)
                if (type == "withdraw") {
                    require(balance >= amount) { "Insufficient withdrawal cash." }
                }
                changedValue(
                    balance, if (type == "deposit") amount else -amount,
                    CASH_LIMIT, "external cash"
                )
                data.put("accountId", account)
                    .put("expectedAccountBalanceToman", balance)
            }
            else -> throw IllegalArgumentException("Unknown financial command.")
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("contractVersion", VERSION)
            .put("operationId", operationId)
            .put("baseSnapshotSha256", snapshot.sha)
            .put("transaction", data)
            .toString(2)
    }
}
