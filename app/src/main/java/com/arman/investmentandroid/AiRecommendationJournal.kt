package com.arman.investmentandroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        file.parentFile?.mkdirs()
        // Never delete the existing journal before replacement. A failed write
        // must preserve the last durable recommendation/decision state.
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            temporary.outputStream().use { stream ->
                stream.write((root.toString(2) + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
                (stream as java.io.FileOutputStream).fd.sync()
            }
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            temporary.delete()
            throw JournalException("AI recommendation journal could not be saved atomically. Previous data was kept.")
        }
    }

    fun exportDocument(): JSONObject {
        val root = loadRoot()
        return JSONObject(root.toString())
    }

    private fun validateImportedRecords(records: JSONArray, label: String) {
        require(records.length() <= MAX_RECORDS) {
            "$label AI journal exceeds the supported record limit. Nothing was imported."
        }
        val identities = mutableSetOf<String>()
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index)
                ?: throw JournalException("$label AI journal contains a non-object record.")
            val id = record.optString("recommendation_id")
            if (id.isBlank() || id.length > 80 || !identities.add(id)) {
                throw JournalException("$label AI journal has a missing or duplicated recommendation ID.")
            }
            if (record.optString("status") !in setOf("pending", "accepted", "rejected")) {
                throw JournalException("$label AI journal has an invalid recommendation status.")
            }
            try {
                Instant.parse(record.getString("created_at"))
            } catch (_: Exception) {
                throw JournalException("$label AI journal has an invalid recommendation timestamp.")
            }
            // Old journal exports may have only metadata. When the complete
            // record is present, independently enforce the strict AI contracts.
            val hasSnapshot = record.has("snapshot") && !record.isNull("snapshot")
            val hasRecommendation = record.has("recommendation") && !record.isNull("recommendation")
            if (hasSnapshot != hasRecommendation) {
                throw JournalException("$label AI journal has a partial recommendation record.")
            }
            if (hasSnapshot) {
                val snapshot = record.optJSONObject("snapshot")
                val recommendation = record.optJSONObject("recommendation")
                if (snapshot == null || recommendation == null) {
                    throw JournalException("$label AI journal has invalid recommendation JSON.")
                }
                try {
                    AiAdvisorContract.assertSnapshotSafe(snapshot)
                    AiAdvisorRecommendation.validate(recommendation)
                } catch (_: Exception) {
                    throw JournalException("$label AI journal contains an unsafe recommendation.")
                }
            }
        }
    }

    private fun mergeRecord(left: JSONObject, right: JSONObject): JSONObject {
        // A recommendation ID must always refer to the same original event.
        // Never let a cloud/manual import silently rewrite immutable advice.
        if (left.optString("created_at") != right.optString("created_at")) {
            throw JournalException("AI recommendation ID refers to different creation times.")
        }
        for (field in listOf("snapshot", "recommendation", "model")) {
            if (!left.isNull(field) && !right.isNull(field) &&
                left.opt(field).toString() != right.opt(field).toString()
            ) {
                throw JournalException("AI recommendation ID refers to conflicting $field data.")
            }
        }
        val leftOutcome = left.optJSONObject("outcome")
        val rightOutcome = right.optJSONObject("outcome")
        if (leftOutcome != null && rightOutcome != null &&
            leftOutcome.toString() != rightOutcome.toString()
        ) {
            throw JournalException("AI recommendation outcome differs between devices.")
        }
        val leftApplied = left.optString("applied_at")
        val rightApplied = right.optString("applied_at")
        if (leftApplied.isNotBlank() && rightApplied.isNotBlank() && leftApplied != rightApplied) {
            throw JournalException("AI recommendation applied status differs between devices.")
        }
        val rank = mapOf("pending" to 0, "rejected" to 1, "accepted" to 1)
        val chosen = JSONObject(left.toString())
        val leftStatus = left.optString("status", "pending")
        val rightStatus = right.optString("status", "pending")
        if (
            leftStatus in setOf("accepted", "rejected") &&
            rightStatus in setOf("accepted", "rejected") &&
            leftStatus != rightStatus
        ) {
            throw JournalException(
                "AI recommendation decision differs between devices; resolve it before syncing."
            )
        }
        val leftRank = rank[leftStatus] ?: -1
        val rightRank = rank[rightStatus] ?: -1
        if (rightRank > leftRank) {
            val keys = right.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                chosen.put(key, right.get(key))
            }
        } else {
            val keys = right.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!chosen.has(key) || chosen.isNull(key)) {
                    chosen.put(key, right.get(key))
                }
            }
        }

        if (rightOutcome != null) {
            val mergedOutcome = chosen.optJSONObject("outcome")
                ?.let { JSONObject(it.toString()) }
                ?: JSONObject()
            val keys = rightOutcome.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!rightOutcome.isNull(key)) mergedOutcome.put(key, rightOutcome.get(key))
            }
            chosen.put("outcome", mergedOutcome)
        }
        if (chosen.optString("decision_at").isBlank() && right.optString("decision_at").isNotBlank()) {
            chosen.put("decision_at", right.optString("decision_at"))
        }
        return chosen
    }

    fun mergeDocument(remote: JSONObject?): JSONObject {
        if (remote == null) return exportDocument()
        if (
            remote.optString("format") != FORMAT ||
            remote.optInt("schema_version", -1) != SCHEMA_VERSION ||
            remote.optJSONArray("records") == null
        ) {
            throw JournalException("Remote AI recommendation journal format is invalid.")
        }

        val localRecords = loadRoot().getJSONArray("records")
        val remoteRecords = remote.getJSONArray("records")
        validateImportedRecords(localRecords, "Local")
        validateImportedRecords(remoteRecords, "Imported")
        val merged = linkedMapOf<String, JSONObject>()

        fun absorb(records: JSONArray) {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val id = record.optString("recommendation_id")
                if (id.isBlank()) continue
                merged[id] = merged[id]?.let { mergeRecord(it, record) }
                    ?: JSONObject(record.toString())
            }
        }

        absorb(remoteRecords)
        absorb(localRecords)

        if (merged.size > MAX_RECORDS) {
            throw JournalException("AI journal merge exceeds the record limit; previous data was kept.")
        }
        val values = merged.values.toList()
        val result = JSONObject()
            .put("format", FORMAT)
            .put("schema_version", SCHEMA_VERSION)
            .put("records", JSONArray(values))
        saveRoot(result)
        return JSONObject(result.toString())
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
                val previous = record.optString("status", "pending")
                if (previous in setOf("accepted", "rejected")) {
                    if (previous != status) {
                        throw JournalException("AI decision is final; conflicting change was not saved.")
                    }
                    return JSONObject(record.toString()) // idempotent retry
                }
                require(previous == "pending") { "Unknown AI recommendation status." }
                record.put("status", status)
                record.put("decision_at", decidedAt.toString())
                saveRoot(root)
                return JSONObject(record.toString())
            }
        }
        throw JournalException("AI recommendation was not found.")
    }

    fun markApplied(
        recommendationId: String,
        appliedAt: Instant = Instant.now()
    ): JSONObject {
        val root = loadRoot()
        val records = root.getJSONArray("records")
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            if (record.optString("recommendation_id") != recommendationId) continue
            if (record.optString("status") != "accepted") {
                throw JournalException("Accept the recommendation before applying its targets.")
            }
            record.put("applied_at", appliedAt.toString())
            saveRoot(root)
            return JSONObject(record.toString())
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

    fun compactHistory(
        now: Instant = Instant.now(),
        limit: Int = 12,
        portfolioScope: String? = null
    ): List<AiAdvisorContract.RecommendationOutcome> {
        require(portfolioScope == null ||
            portfolioScope in setOf("android-local", "windows-core-readonly")) {
            "Unknown AI portfolio scope."
        }
        val records = loadRoot().getJSONArray("records")
        val relevant = (0 until records.length())
            .map { records.getJSONObject(it) }
            .filter { record ->
                portfolioScope == null ||
                    record.optJSONObject("snapshot")?.optString(
                        "portfolio_scope", "android-local"
                    ) == portfolioScope
            }
            .takeLast(limit.coerceIn(1, 24))
        val result = mutableListOf<AiAdvisorContract.RecommendationOutcome>()
        for (record in relevant) {
            val status = record.optString("status")
            if (status != "accepted" && status != "rejected") continue
            val created = try { Instant.parse(record.getString("created_at")) } catch (_: Exception) { continue }
            val ageDays = maxOf(0, Duration.between(created, now).toDays().toInt())
            val outcome = record.optJSONObject("outcome")
            result += AiAdvisorContract.RecommendationOutcome(
                recommendationId = record.getString("recommendation_id"),
                ageDays = ageDays,
                accepted = record.optString("status") == "accepted",
                applied = record.optString("applied_at").isNotBlank(),
                outcomePct = outcome?.optDouble("portfolio_return_pct")
                    ?.takeIf { it.isFinite() }
            )
        }
        return result
    }
}
