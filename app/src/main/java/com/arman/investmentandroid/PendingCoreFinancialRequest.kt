package com.arman.investmentandroid

import org.json.JSONObject
import java.util.UUID

/** Preserve one immutable offline command in private Android saved-instance state. */
object PendingCoreFinancialRequest {
    const val STATE_KEY = "pending_core_financial_request_json"
    const val MAX_BYTES = 65536
    fun restore(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        return try {
            val envelope = JSONObject(raw)
            if (envelope.length() != 5 ||
                envelope.optString("format") != CoreFinancialProposal.FORMAT ||
                envelope.optInt("contractVersion", -1) != CoreFinancialProposal.VERSION ||
                !Regex("[0-9a-f]{64}").matches(envelope.optString("baseSnapshotSha256"))
            ) return null
            val id = envelope.optString("operationId")
            if (UUID.fromString(id).toString() != id) return null
            val tx = envelope.optJSONObject("transaction") ?: return null
            if (tx.optString("type") !in setOf("buy", "sell", "transfer", "deposit", "withdraw"))
                return null
            raw  // Keep exact bytes and UUID; never regenerate for retry.
        } catch (_: Exception) {
            null
        }
    }
    fun operationId(raw: String?): String? =
        restore(raw)?.let { JSONObject(it).getString("operationId") }
}
