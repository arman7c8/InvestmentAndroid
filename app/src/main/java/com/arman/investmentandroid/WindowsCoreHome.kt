package com.arman.investmentandroid

import org.json.JSONObject
import java.math.BigDecimal
import java.util.Base64

/**
 * A display model built exclusively from a checksum-verified Windows Core
 * interchange. Never converts Core rows into editable Android assets/events.
 */
object WindowsCoreHome {
    data class Holding(
        val id: String,
        val name: String,
        val quantity: Double,
        val valueToman: Double?,
        val groupName: String?,
        val effectiveTargetPercent: Double?
    )
    data class CashAccount(val id: String, val balanceToman: Double)
    data class Overview(
        val sha256: String,
        val schema: Int,
        val transactionCount: Int,
        val holdings: List<Holding>,
        val cashAccounts: List<CashAccount>,
        val pricedHoldingsToman: Double,
        val cashToman: Double,
        val missingPriceCount: Int,
        val nonTargetAssetsToman: Double = 0.0
    ) {
        // A missing nonzero holding quote must not silently become zero net worth.
        val completeValueToman: Double?
            get() = if (missingPriceCount == 0)
                BigDecimal.valueOf(pricedHoldingsToman)
                    .add(BigDecimal.valueOf(cashToman))
                    .add(BigDecimal.valueOf(nonTargetAssetsToman)).toDouble()
            else null
        val partialValueToman: Double
            get() = BigDecimal.valueOf(pricedHoldingsToman)
                .add(BigDecimal.valueOf(cashToman))
                .add(BigDecimal.valueOf(nonTargetAssetsToman)).toDouble()
    }

    /** Throws on malformed/altered files before any local snapshot is saved. */
    fun inspect(raw: String): Overview {
        val verified = CoreSnapshotPreview.inspect(raw)
        val envelope = JSONObject(raw)
        val decoded = Base64.getDecoder().decode(envelope.getString("payloadBase64"))
        val payload = JSONObject(String(decoded, Charsets.UTF_8))
        val preview = payload.getJSONObject("preview")
        val settings = payload.getJSONObject("tables").optJSONObject("portfolio_settings")
        // Windows Core v11 stores excluded fixed assets as a *single* valuation
        // setting, not as positions. Use the checksum-verified table only.
        val nonTargetAssetsToman = if (settings == null) 0.0 else {
            val columns = settings.getJSONArray("columns")
            val keys = (0 until columns.length()).map(columns::getString)
            val keyColumn = keys.indexOf("key")
            val valueColumn = keys.indexOf("value")
            require(keyColumn >= 0 && valueColumn >= 0) {
                "Windows Core settings table is missing key/value columns."
            }
            val rows = settings.getJSONArray("rows")
            var found: Double? = null
            for (index in 0 until rows.length()) {
                val row = rows.getJSONArray(index)
                if (row.getString(keyColumn) != "other_assets_toman") continue
                require(found == null) { "Duplicate Windows non-target asset setting." }
                val raw = row.get(valueColumn)
                require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() >= 0.0) {
                    "Invalid Windows non-target asset amount."
                }
                found = raw.toDouble()
            }
            found ?: 0.0
        }
        val policyAssets = verified.policy?.assets?.associateBy { it.id } ?: emptyMap()
        val groupNames = verified.policy?.groups?.associate { it.id to it.name } ?: emptyMap()

        val holdings = preview.getJSONArray("holdings")
        val items = (0 until holdings.length()).map { index ->
            val item = holdings.getJSONObject(index)
            val id = item.getString("id")
            val qty = item.getDouble("quantity")
            val value = if (item.isNull("value_toman")) null else item.getDouble("value_toman")
            require(qty.isFinite() && (value == null || value.isFinite())) {
                "Non-finite Windows holding."
            }
            val category = policyAssets[id]?.groupId
            Holding(
                id = id,
                name = item.getString("name"),
                quantity = qty,
                valueToman = value,
                groupName = category?.let(groupNames::get),
                effectiveTargetPercent = policyAssets[id]?.effective
            )
        }
        require(items.size == verified.positions.size &&
            items.zip(verified.positions).all { (holding, position) ->
                holding.id == position.id && holding.quantity == position.quantity
            }) { "Windows holdings changed after verification." }

        val cash = preview.getJSONArray("cashAccounts")
        val accounts = (0 until cash.length()).map { index ->
            val item = cash.getJSONObject(index)
            CashAccount(item.getString("id"), item.getDouble("balance_toman"))
        }
        require(accounts.size == verified.cashBalances.size &&
            accounts.zip(verified.cashBalances).all { (item, checked) ->
                item.id == checked.id && item.balanceToman == checked.balanceToman
            }) { "Windows cash accounts changed after verification." }

        // Accumulate as decimals to avoid avoidable float summation drift.
        fun sum(values: List<Double>): Double =
            values.fold(BigDecimal.ZERO) { total, amount ->
                require(amount.isFinite()) { "Non-finite Windows portfolio valuation." }
                total.add(BigDecimal.valueOf(amount))
            }.toDouble().also { require(it.isFinite()) }

        val missing = items.count { it.valueToman == null && it.quantity != 0.0 }
        val result = Overview(
            sha256 = verified.sha,
            schema = verified.schema,
            transactionCount = verified.transactions,
            holdings = items,
            cashAccounts = accounts,
            pricedHoldingsToman = sum(items.mapNotNull { it.valueToman }),
            cashToman = sum(accounts.map { it.balanceToman }),
            missingPriceCount = missing,
            nonTargetAssetsToman = nonTargetAssetsToman
        )
        require(result.partialValueToman.isFinite()) {
            "Windows Core total net worth is outside the supported range."
        }
        return result
    }
}
