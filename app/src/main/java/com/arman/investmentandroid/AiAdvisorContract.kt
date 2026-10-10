package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.round

object AiAdvisorContract {
    const val FORMAT = "investment.ai.snapshot"
    const val SCHEMA_VERSION = 1
    private const val MAX_ALLOCATIONS = 128
    private const val MAX_HISTORY = 24
    private val allowedPeriods = setOf("1d", "7d", "30d", "90d", "1y")
    private val prohibitedKeyParts = setOf(
        "amount", "value", "quantity", "price", "balance", "cost",
        "toman", "rial", "dollar", "bank", "account", "iban", "card",
        "email", "phone", "address", "credential", "token", "secret",
        "transaction"
    )

    class ContractException(message: String) : IllegalArgumentException(message)

    data class Allocation(
        val scope: String,
        val publicKey: String,
        val currentPct: Double,
        val targetPct: Double,
        val groupKey: String? = null,
        val performancePct: Double? = null
    )

    data class RecommendationOutcome(
        val recommendationId: String,
        val ageDays: Int,
        val accepted: Boolean? = null,
        val applied: Boolean? = null,
        val outcomePct: Double? = null
    )

    data class AtlasSignal(
        val publicKey: String,
        val direction: String,
        val confidence: Double,
        val note: String = ""
    )

    data class AtlasContext(
        val asOf: String,
        val riskRegime: String,
        val brief: String,
        val signals: List<AtlasSignal> = emptyList()
    )

    private fun finitePercent(
        value: Double,
        field: String,
        low: Double = -10000.0,
        high: Double = 10000.0
    ): Double {
        if (!value.isFinite() || value < low || value > high) {
            throw ContractException(field + " is outside the supported percentage range.")
        }
        return round(value * 1_000_000.0) / 1_000_000.0
    }

    private fun publicKey(value: String?, fallback: String): String {
        val normalized = (value ?: "")
            .trim()
            .uppercase(Locale.US)
            .replace(Regex("[^A-Z0-9._:-]+"), "-")
            .trim('-')
            .take(32)
        return normalized.ifBlank { fallback }
    }

    fun buildSnapshot(
        allocations: List<Allocation>,
        portfolioPerformancePct: Map<String, Double> = emptyMap(),
        recommendationHistory: List<RecommendationOutcome> = emptyList(),
        atlasContext: AtlasContext? = null,
        generatedAt: String
    ): JSONObject {
        require(allocations.size <= MAX_ALLOCATIONS) {
            "Too many allocation rows for one AI snapshot."
        }

        val rows = JSONArray()
        allocations.forEachIndexed { index, item ->
            val scope = item.scope.lowercase(Locale.US)
            if (scope != "group" && scope != "asset" && scope != "portfolio_asset") {
                throw ContractException("Allocation scope must be group, asset, or portfolio_asset.")
            }
            val row = JSONObject()
                .put("scope", scope)
                .put("public_key", publicKey(item.publicKey, "ROW-" + (index + 1)))
                .put("current_pct", finitePercent(item.currentPct, "current_pct", 0.0, 100.0))
                .put("target_pct", finitePercent(item.targetPct, "target_pct", 0.0, 100.0))
            if (scope == "asset") {
                row.put("group_key", publicKey(item.groupKey, "UNSPECIFIED"))
            }
            item.performancePct?.let {
                row.put("performance_pct", finitePercent(it, "performance_pct"))
            }
            rows.put(row)
        }

        val performance = JSONObject()
        portfolioPerformancePct.forEach { (period, pct) ->
            val key = period.lowercase(Locale.US)
            if (key in allowedPeriods) {
                performance.put(key, finitePercent(pct, "portfolio_performance_pct." + key))
            }
        }

        val history = JSONArray()
        recommendationHistory.takeLast(MAX_HISTORY).forEachIndexed { index, item ->
            val record = JSONObject()
                .put("recommendation_id", publicKey(item.recommendationId, "REC-" + (index + 1)))
                .put("age_days", item.ageDays.coerceIn(0, 3650))
            item.accepted?.let { record.put("accepted", it) }
            item.applied?.let { record.put("applied", it) }
            item.outcomePct?.let {
                record.put("outcome_pct", finitePercent(it, "outcome_pct"))
            }
            history.put(record)
        }

        val privacy = JSONObject()
            .put("money_included", false)
            .put("units_included", false)
            .put("financial_ids_included", false)
            .put("personal_identity_included", false)

        val payload = JSONObject()
            .put("format", FORMAT)
            .put("schema_version", SCHEMA_VERSION)
            .put("generated_at", generatedAt.take(64))
            .put("privacy", privacy)
            .put("allocations", rows)
            .put("portfolio_performance_pct", performance)
            .put("recommendation_history", history)

        atlasContext?.let { atlas ->
            val regime = atlas.riskRegime.lowercase(Locale.US).let {
                if (it in setOf("low", "medium", "high", "extreme", "unknown")) it else "unknown"
            }
            val signals = JSONArray()
            atlas.signals.take(32).forEachIndexed { index, signal ->
                val direction = signal.direction.lowercase(Locale.US).let {
                    if (it in setOf("bullish", "bearish", "neutral", "risk")) it else "neutral"
                }
                signals.put(
                    JSONObject()
                        .put("public_key", publicKey(signal.publicKey, "SIGNAL-" + (index + 1)))
                        .put("direction", direction)
                        .put("confidence", finitePercent(signal.confidence, "atlas.signal.confidence", 0.0, 100.0))
                        .put("note", signal.note.take(280))
                )
            }
            payload.put(
                "atlas_context",
                JSONObject()
                    .put("as_of", atlas.asOf.take(40))
                    .put("risk_regime", regime)
                    .put("brief", atlas.brief.take(2000))
                    .put("signals", signals)
            )
        }

        assertSnapshotSafe(payload)
        return payload
    }

    fun assertSnapshotSafe(payload: JSONObject) {
        if (payload.optString("format") != FORMAT ||
            payload.optInt("schema_version", -1) != SCHEMA_VERSION
        ) {
            throw ContractException("Unsupported AI snapshot.")
        }
        rejectSensitiveKeys(payload, "payload")
        val privacy = payload.optJSONObject("privacy")
            ?: throw ContractException("AI snapshot privacy declaration is missing.")
        val flags = listOf(
            "money_included",
            "units_included",
            "financial_ids_included",
            "personal_identity_included"
        )
        if (flags.any { privacy.optBoolean(it, true) }) {
            throw ContractException("AI snapshot privacy declaration is invalid.")
        }
    }

    private fun rejectSensitiveKeys(value: Any?, path: String) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val raw = keys.next()
                    val key = raw.lowercase(Locale.US)
                    if (prohibitedKeyParts.any { key.contains(it) }) {
                        throw ContractException(
                            "Sensitive field is not allowed in AI payload: " + path + "." + raw
                        )
                    }
                    rejectSensitiveKeys(value.opt(raw), path + "." + raw)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    rejectSensitiveKeys(value.opt(index), path + "[" + index + "]")
                }
            }
        }
    }
}
