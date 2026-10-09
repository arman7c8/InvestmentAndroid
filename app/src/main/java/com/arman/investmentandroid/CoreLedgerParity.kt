package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Verify the Windows projection against independent raw Core ledger rows.
 *
 * Read-only: NEVER convert these events into Android transactions. A mismatch
 * is an invalid preview, not a reason to pick one side and discard history.
 */
object CoreLedgerParity {
    private class Table(tables: JSONObject, name: String) {
        private val data = tables.getJSONObject(name)
        private val columns = data.getJSONArray("columns")
        private val rows = data.getJSONArray("rows")
        private val lookup = (0 until columns.length()).associateBy { columns.getString(it) }

        fun records(): List<JSONObject> = (0 until rows.length()).map { index ->
            val row = rows.getJSONArray(index)
            JSONObject().apply {
                for ((name, column) in lookup) put(name, row.get(column))
            }
        }
    }

    private fun string(item: JSONObject, name: String): String =
        (item.opt(name) as? String).orEmpty()

    private fun finite(item: JSONObject, name: String): Double {
        val number = item.opt(name)
        require(number is Number && number.toDouble().isFinite()) {
            "Missing or non-finite Core amount: $name."
        }
        return number.toDouble()
    }

    private fun sameNumber(a: Double, b: Double): Boolean =
        a.isFinite() && b.isFinite() &&
            abs(a - b) <= 1e-12 * max(1.0, max(abs(a), abs(b)))

    fun verify(tables: JSONObject, preview: JSONObject) {
        val snapshots = Table(tables, "imported_snapshots").records()
        require(snapshots.isNotEmpty()) { "Core opening snapshot is missing." }
        val latestId = snapshots.maxOf { finite(it, "id") }

        val balances = linkedMapOf<String, Double>()
        for (item in Table(tables, "opening_accounts").records()) {
            if (finite(item, "snapshot_id") != latestId) continue
            val id = string(item, "account_id")
            require(id.isNotBlank() && !balances.containsKey(id)) { "Invalid Core opening account." }
            balances[id] = finite(item, "balance_toman")
        }

        val quantities = linkedMapOf<String, Double>()
        for (item in Table(tables, "opening_positions").records()) {
            if (finite(item, "snapshot_id") != latestId) continue
            val id = string(item, "asset_id")
            require(id.isNotBlank() && !quantities.containsKey(id)) { "Invalid Core opening position." }
            quantities[id] = finite(item, "quantity")
        }

        val assets = Table(tables, "assets").records()
        val assetIds = assets.map { string(it, "id") }
        require(assetIds.all { it.isNotBlank() } && assetIds.distinct().size == assetIds.size) {
            "Duplicate or missing Core asset id."
        }
        require(quantities.keys.all { it in assetIds }) { "Opening position has no asset definition." }
        val voided = Table(tables, "transaction_voids").records()
            .map { string(it, "transaction_id") }.toSet()
        val transactions = Table(tables, "transactions").records()
        require(preview.getInt("activeTransactionCount") == transactions.count {
            string(it, "id") !in voided
        }) { "Active Core transaction count differs." }

        for (transaction in transactions) {
            if (string(transaction, "id") in voided) continue
            val kind = string(transaction, "type")
            val amount = finite(transaction, "amount_toman")
            require(amount > 0.0) { "Invalid financial event amount." }
            val from = string(transaction, "source").ifBlank { "wallet" }
            val to = string(transaction, "destination").ifBlank { "wallet" }
            when (kind) {
                "withdraw", "buy", "transfer" ->
                    balances[from] = (balances[from] ?: 0.0) - amount
                "deposit", "sell" -> Unit
                else -> throw IllegalArgumentException("Unknown Core event type: $kind")
            }
            if (kind == "deposit" || kind == "sell" || kind == "transfer") {
                balances[to] = (balances[to] ?: 0.0) + amount
            }
            if (kind == "buy" || kind == "sell") {
                val id = string(transaction, "asset_id")
                require(id in assetIds) { "Trade has no known Core asset." }
                val quantity = finite(transaction, "quantity")
                require(quantity >= 0.0) { "Invalid traded quantity." }
                quantities[id] = (quantities[id] ?: 0.0) +
                    (if (kind == "buy") quantity else -quantity)
            }
        }

        for (correction in Table(tables, "quantity_corrections").records()) {
            val id = string(correction, "asset_id")
            require(id in assetIds) { "Correction has no Core asset." }
            quantities[id] = (quantities[id] ?: 0.0) + finite(correction, "delta")
        }

        val holdings = preview.getJSONArray("holdings")
        val seenAssets = mutableSetOf<String>()
        require(holdings.length() == assetIds.size) { "Missing Core holdings." }
        for (index in 0 until holdings.length()) {
            val item = holdings.getJSONObject(index)
            val id = string(item, "id")
            require(id in assetIds && seenAssets.add(id)) { "Unknown or duplicate Core holding." }
            val computed = quantities[id] ?: 0.0
            require(sameNumber(computed, finite(item, "quantity"))) {
                "Core holding quantity differs from ledger: $id."
            }
        }

        // Validate the market-price projection independently, including
        // native USDT quotes repriced with the latest reference asset.
        // A valid SHA alone does not prove the displayed valuation is correct.
        val latestQuotes = linkedMapOf<String, JSONObject>()
        val prices = Table(tables, "prices").records()
        prices.withIndex().sortedWith(
            compareBy<IndexedValue<JSONObject>> { string(it.value, "observed_at") }
                .thenBy { it.index }
        ).forEach { (_, row) ->
            val id = string(row, "asset_id")
            require(id in assetIds) { "Price record has an unknown Core asset." }
            latestQuotes[id] = row
        }

        fun currentPrice(assetId: String, seen: Set<String> = emptySet()): Double? {
            require(assetId !in seen) { "Circular Core FX reference." }
            val quote = latestQuotes[assetId] ?: return null
            val native = if (!quote.has("native_price") || quote.isNull("native_price")) null
                else finite(quote, "native_price")
            val result = if (native != null) {
                val reference = string(quote, "reference_asset_id")
                if (reference.isBlank()) return null
                require(reference in assetIds) { "Unknown Core FX reference." }
                val rate = currentPrice(reference, seen + assetId) ?: return null
                native * rate
            } else finite(quote, "price_toman")
            require(result.isFinite() && result > 0.0) { "Invalid current Core quote." }
            return result
        }

        for (index in 0 until holdings.length()) {
            val item = holdings.getJSONObject(index)
            val id = string(item, "id")
            val quote = currentPrice(id)
            if (quote == null) {
                require(!item.has("value_toman") || item.isNull("value_toman")) {
                    "Unpriced Core holding has a fabricated value: $id."
                }
            } else {
                val quantity = quantities[id] ?: 0.0
                val expectedValue = quantity * quote
                require(expectedValue.isFinite() &&
                    !item.isNull("value_toman") &&
                    sameNumber(expectedValue, finite(item, "value_toman"))) {
                    "Core holding valuation differs from current price/FX ledger: $id."
                }
            }
        }

        val accounts = preview.getJSONArray("cashAccounts")
        val seenAccounts = mutableSetOf<String>()
        val expected = balances.filterKeys { it != "external" }
        require(accounts.length() == expected.size) { "Core cash account count differs." }
        for (index in 0 until accounts.length()) {
            val item = accounts.getJSONObject(index)
            val id = string(item, "id")
            require(id in expected && seenAccounts.add(id)) {
                "Unknown or duplicate Core cash account."
            }
            require(sameNumber(expected.getValue(id), finite(item, "balance_toman"))) {
                "Core cash balance differs from ledger: $id."
            }
        }
    }
}
