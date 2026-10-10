package com.arman.investmentandroid

import org.json.JSONObject
import kotlin.math.abs

/**
 * Offline/manual advisor bridge. The model reply must match the exact current
 * percentage-only snapshot, not a stale or unrelated portfolio. No targets,
 * trades or Windows Core records are ever applied by this bridge.
 */
object AiManualRecommendationGate {
    fun validateAgainstSnapshot(snapshot: JSONObject, reply: JSONObject) {
        AiAdvisorContract.assertSnapshotSafe(snapshot)
        AiAdvisorRecommendation.validate(reply)
        val rows = snapshot.getJSONArray("allocations")
        val expected = linkedMapOf<String, Double>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            require(row.getString("scope") == "portfolio_asset") {
                "Manual AI review requires portfolio-asset percentages."
            }
            val key = row.getString("public_key")
            require(key !in expected) { "Duplicate public keys in AI snapshot." }
            expected[key] = row.getDouble("current_pct")
        }
        val proposals = reply.getJSONArray("suggested_targets")
        require(proposals.length() == expected.size) {
            "AI targets do not cover the current portfolio exactly."
        }
        val seen = mutableSetOf<String>()
        for (index in 0 until proposals.length()) {
            val row = proposals.getJSONObject(index)
            require(row.getString("scope") == "portfolio_asset") {
                "AI result must use portfolio-asset targets only."
            }
            val key = row.getString("public_key")
            val current = expected[key]
                ?: throw IllegalArgumentException("AI result names an unknown portfolio asset.")
            require(seen.add(key)) { "AI result repeats a portfolio asset." }
            require(abs(current - row.getDouble("current_pct")) < 0.25) {
                "AI result was generated for a different portfolio. Re-export current percentages."
            }
        }
    }
}
