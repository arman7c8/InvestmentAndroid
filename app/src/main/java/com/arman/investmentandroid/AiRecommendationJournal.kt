package com.arman.investmentandroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class AiRecommendationJournal private constructor(private val file: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "ai-recommendation-journal.json"))
    internal constructor(path: String) : this(File(path))
    companion object {
        const val FORMAT = "investment.ai.journal"
        const val SCHEMA_VERSION = 1
        private const val MAX_RECORDS = 200
    }

    class JournalException(message: String) : IllegalArgumentException(message)


    private fun loadRoot(): JSONObject {
        if (!file.isFile) {
            return JSONObject()
                .put("format", FORMAT)
                .put("schema_version", SCHEMA_VERSION)
                .put("records", JSONArray())
        }
        return try {
            val value = JSONObject(file.readText(Charsets.UTF_8))
            if (value.optString("format") != FORMAT ||
                value.optInt("schema_version", -1) != SCHEMA_VERSION ||
                value.optJSONArray("records") == null
            ) throw JournalException("AI recommendation journal format is invalid.")
            value
        } catch (exc: JournalException) {
            throw exc
        } catch (_: Exception) {
            throw JournalException("AI recommendation journal could not be read.")
        }
    }

    private fun saveRoot(root: JSONObject) {
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(root.toString(2) + "\n", Charsets.UTF_8)
            if (file.exists() && !file.delete()) {
                temporary.delete()
                throw JournalException("AI recommendation journal could not be replaced.")
            }
            if (!temporary.renameTo(file)) {
                temporary.delete()
                throw JournalException("AI recommendation journal could not be saved.")
            }
        } catch (exc: JournalException) {
            throw exc
        } catch (_: Exception) {
            throw JournalException("AI recommendation journal could not be saved.")
        }
    }

    fun listRecords(): List<JSONObject> {
        val records = loadRoot().getJSONArray("records")
        val result = mutableListOf<JSONObject>()
        for (index in 0 until records.length()) {
            result += JSONObject(records.getJSONObject(index).toString())
        }
        return result
    }

    fun recordRecommendation(
        snapshot: JSONObject,
        recommendation: JSONObject,
        model: String,
        createdAt: Instant = Instant.now()
    ): JSONObject {
        AiAdvisorContract.assertSnapshotSafe(snapshot)
        AiAdvisorRecommendation.validate(recommendation)
        val id = "REC-" + UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
        val reviewDays = recommendation.optInt("review_after_days", 7).coerceIn(1, 90)
        val record = JSONObject()
            .put("recommendation_id", id)
            .put("created_at", createdAt.toString())
            .put("status", "pending")
            .put("decision_at", JSONObject.NULL)
            .put("review_due_at", createdAt.plus(reviewDays.toLong(), ChronoUnit.DAYS).toString())
            .put("model", model.take(80))
            .put("snapshot", JSONObject(snapshot.toString()))
            .put("recommendation", JSONObject(recommendation.toString()))
            .put("outcome", JSONObject.NULL)

        val root = loadRoot()
        val old = root.getJSONArray("records")
        val records = JSONArray()
        val start = maxOf(0, old.length() - (MAX_RECORDS - 1))
        for (index in start until old.length()) records.put(old.getJSONObject(index))
        records.put(record)
        root.put("records", records)
        saveRoot(root)
        return JSONObject(record.toString())
    }

    fun setDecision(
        recommendationId: String,
        status: String,
        decidedAt: Instant = Instant.now()
    ): JSONObject {
        if (status !in setOf("accepted", "rejected")) {
            throw JournalException("Decision must be accepted or rejected.")
        }
        val root = loadRoot()
        val records = root.getJSONArray("records")
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            if (record.optString("recommendation_id") == recommendationId) {
                record.put("status", status)
                record.put("decision_at", decidedAt.toString())
                saveRoot(root)
                return JSONObject(record.toString())
            }
        }
        throw JournalException("AI recommendation was not found.")
    }

    fun recordOutcome(
        recommendationId: String,
        portfolioReturnPct: Double,
        maxDrawdownPct: Double? = null,
        evaluatedAt: Instant = Instant.now()
    ): JSONObject {
        if (!portfolioReturnPct.isFinite()) throw JournalException("Portfolio return must be finite.")
        if (maxDrawdownPct != null && !maxDrawdownPct.isFinite()) {
            throw JournalException("Drawdown must be finite.")
        }
        val root = loadRoot()
        val records = root.getJSONArray("records")
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            if (record.optString("recommendation_id") != recommendationId) continue
            if (record.optString("status") != "accepted") {
                throw JournalException("Only accepted recommendations can receive an outcome.")
            }
            val outcome = JSONObject()
                .put("evaluated_at", evaluatedAt.toString())
                .put("portfolio_return_pct", portfolioReturnPct)
            if (maxDrawdownPct != null) outcome.put("max_drawdown_pct", maxDrawdownPct)
            record.put("outcome", outcome)
            saveRoot(root)
            return JSONObject(record.toString())
        }
        throw JournalException("AI recommendation was not found.")
    }

    fun compactHistory(now: Instant = Instant.now(), limit: Int = 12): List<AiAdvisorContract.RecommendationOutcome> {
        val records = loadRoot().getJSONArray("records")
        val start = maxOf(0, records.length() - limit.coerceIn(1, 24))
        val result = mutableListOf<AiAdvisorContract.RecommendationOutcome>()
        for (index in start until records.length()) {
            val record = records.getJSONObject(index)
            val status = record.optString("status")
            if (status != "accepted" && status != "rejected") continue
            val created = try { Instant.parse(record.getString("created_at")) } catch (_: Exception) { continue }
            val ageDays = maxOf(0, Duration.between(created, now).toDays().toInt())
            val outcome = record.optJSONObject("outcome")
            result += AiAdvisorContract.RecommendationOutcome(
                recommendationId = record.getString("recommendation_id"),
                ageDays = ageDays,
                accepted = record.optString("status") == "accepted",
                outcomePct = outcome?.optDouble("portfolio_return_pct")
                    ?.takeIf { it.isFinite() }
            )
        }
        return result
    }
}
