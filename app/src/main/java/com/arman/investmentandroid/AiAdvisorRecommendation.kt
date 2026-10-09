package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

object AiAdvisorRecommendation {
    private val risks = setOf("low", "medium", "high", "extreme", "unknown")
    private val forbidden = setOf(
        "amount", "value", "quantity", "price", "balance", "cost",
        "toman", "rial", "dollar", "bank", "account", "iban", "card",
        "email", "phone", "address", "credential", "token", "secret",
        "transaction", "buy", "sell"
    )

    class RecommendationException(message: String) : IllegalArgumentException(message)

    fun promptContract(): String =
        "Return ONLY JSON with format investment.ai.recommendation, schema_version 1, " +
            "summary, market_risk, portfolio_risk, confidence_pct, suggested_targets, " +
            "warnings, review_after_days. Each target scope must be group, portfolio_asset, " +
            "or asset. Include public_key, current_pct, suggested_pct, reason; asset rows " +
            "also require group_key. Top-level group/portfolio_asset suggested targets must " +
            "sum to 100. Never include money, prices, quantities, bank/account identifiers, " +
            "transactions, or buy/sell instructions."

    fun parse(rawText: String): JSONObject {
        var raw = rawText.trim()
        if (raw.startsWith("```")) {
            val lines = raw.lines().toMutableList()
            if (lines.isNotEmpty()) lines.removeAt(0)
            if (lines.isNotEmpty() && lines.last().trim() == "```") lines.removeAt(lines.lastIndex)
            raw = lines.joinToString("\n").trim()
        }
        val value = try { JSONObject(raw) } catch (_: Exception) {
            throw RecommendationException("ChatGPT did not return valid recommendation JSON.")
        }
        validate(value)
        return value
    }

    fun validate(value: JSONObject) {
        rejectSensitive(value, "response")
        if (value.optString("format") != "investment.ai.recommendation" ||
            value.optInt("schema_version", -1) != 1
        ) throw RecommendationException("Unsupported AI recommendation format.")

        if (value.optString("market_risk", "unknown").lowercase(Locale.US) !in risks ||
            value.optString("portfolio_risk", "unknown").lowercase(Locale.US) !in risks
        ) throw RecommendationException("Invalid AI risk level.")

        val confidence = value.optDouble("confidence_pct", Double.NaN)
        if (!confidence.isFinite() || confidence !in 0.0..100.0) {
            throw RecommendationException("Invalid AI confidence.")
        }

        val targets = value.optJSONArray("suggested_targets")
            ?: throw RecommendationException("AI recommendation has no target list.")
        if (targets.length() == 0 || targets.length() > 128) {
            throw RecommendationException("AI target list is invalid.")
        }

        var topLevelSum = 0.0
        var topLevelCount = 0
        val assetSums = mutableMapOf<String, Double>()
        for (index in 0 until targets.length()) {
            val row = targets.optJSONObject(index)
                ?: throw RecommendationException("AI target row is invalid.")
            val scope = row.optString("scope").lowercase(Locale.US)
            if (scope !in setOf("group", "portfolio_asset", "asset")) {
                throw RecommendationException("AI target scope is invalid.")
            }
            val current = row.optDouble("current_pct", Double.NaN)
            val suggested = row.optDouble("suggested_pct", Double.NaN)
            if (!current.isFinite() || current !in 0.0..100.0 ||
                !suggested.isFinite() || suggested !in 0.0..100.0
            ) throw RecommendationException("AI target percentages are invalid.")
            if (row.optString("public_key").isBlank()) {
                throw RecommendationException("AI target key is missing.")
            }

            if (scope == "asset") {
                val group = row.optString("group_key")
                if (group.isBlank()) throw RecommendationException("AI asset group key is missing.")
                assetSums[group] = (assetSums[group] ?: 0.0) + suggested
            } else {
                topLevelSum += suggested
                topLevelCount += 1
            }
        }
        if (topLevelCount > 0 && abs(topLevelSum - 100.0) > 0.25) {
            throw RecommendationException("AI top-level targets must sum to 100%.")
        }
        assetSums.forEach { entry ->
            if (abs(entry.value - 100.0) > 0.25) {
                throw RecommendationException("AI asset targets for " + entry.key + " must sum to 100%.")
            }
        }

        val review = value.optInt("review_after_days", 7)
        if (review !in 1..90) throw RecommendationException("AI review interval is invalid.")
    }

    private fun rejectSensitive(value: Any?, path: String) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val raw = keys.next()
                    val lower = raw.lowercase(Locale.US)
                    if (forbidden.any { lower.contains(it) }) {
                        throw RecommendationException("Forbidden AI recommendation field: " + path + "." + raw)
                    }
                    rejectSensitive(value.opt(raw), path + "." + raw)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) rejectSensitive(value.opt(i), path + "[" + i + "]")
        }
    }
}
