package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONException

/**
 * Protect the existing local ledger bytes from being silently overwritten after
 * a failed parse. This validates identity and shape; it does not rewrite,
 * migrate or reinterpret a user's historical transactions.
 */
object LocalHistorySafety {
    fun requireReadableBeforeOverwrite(raw: String?): Int {
        require(!raw.isNullOrBlank()) {
            "Stored transaction history is empty or unreadable. No data was overwritten."
        }
        val rows = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            throw IllegalArgumentException(
                "Stored transaction history is damaged. No data was overwritten."
            )
        }
        val ids = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index)
                ?: throw IllegalArgumentException(
                    "Stored transaction history has an invalid row. No data was overwritten."
                )
            if (item.has("id")) {
                val id = item.optString("id", "").trim()
                require(id.isNotEmpty() && ids.add(id)) {
                    "Stored history has an empty or duplicate transaction ID. No data was overwritten."
                }
            }
        }
        return rows.length()
    }
}
