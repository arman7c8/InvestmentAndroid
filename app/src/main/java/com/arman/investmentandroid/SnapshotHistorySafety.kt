package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONException

/** Reject loss of valid local valuation history after a failed JSON parse. */
object SnapshotHistorySafety {
    fun requireReadableBeforeOverwrite(raw: String?): Int {
        require(!raw.isNullOrBlank()) {
            "Local valuation history is missing. No history was overwritten."
        }
        val rows = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            throw IllegalArgumentException(
                "Local valuation history is damaged. No history was overwritten."
            )
        }
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index)
                ?: throw IllegalArgumentException(
                    "Invalid local valuation history row. No history was overwritten."
                )
            val amount = item.opt("totalValue")
            val instant = item.opt("timestamp")
            require(amount is Number && amount.toDouble().isFinite() &&
                amount.toDouble() >= 0.0 && instant is Number &&
                instant.toDouble().isFinite() && instant.toLong() >= 0L) {
                "Invalid valuation timestamp or amount. No history was overwritten."
            }
        }
        return rows.length()
    }
}
