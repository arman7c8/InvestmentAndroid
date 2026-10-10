package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.nio.file.Files

class AiRecommendationJournalTest {
    private fun recommendation(): JSONObject = JSONObject()
        .put("format", "investment.ai.recommendation")
        .put("schema_version", 1)
        .put("summary", "Diversify.")
        .put("market_risk", "medium")
        .put("portfolio_risk", "medium")
        .put("confidence_pct", 70)
        .put("suggested_targets", JSONArray()
            .put(JSONObject().put("scope","portfolio_asset").put("public_key","BTC").put("current_pct",60).put("suggested_pct",55).put("reason","Reduce"))
            .put(JSONObject().put("scope","portfolio_asset").put("public_key","GOLD-1").put("current_pct",40).put("suggested_pct",45).put("reason","Diversify")))
        .put("warnings", JSONArray())
        .put("review_after_days", 7)

    @Test
    fun lifecycleProducesCompactHistory() {
        val folder = Files.createTempDirectory("investment-ai-journal")
        val journal = AiRecommendationJournal(folder.resolve("journal.json").toString())
        val snapshot = AiAdvisorContract.buildSnapshot(
            allocations = listOf(
                AiAdvisorContract.Allocation("portfolio_asset","BTC",60.0,60.0),
                AiAdvisorContract.Allocation("portfolio_asset","GOLD-1",40.0,40.0)
            ),
            generatedAt = "2026-10-09T12:00:00Z"
        )
        val record = journal.recordRecommendation(
            snapshot, recommendation(), "model-a", Instant.parse("2026-10-09T12:00:00Z")
        )
        journal.setDecision(record.getString("recommendation_id"), "accepted", Instant.parse("2026-10-09T13:00:00Z"))
        journal.recordOutcome(record.getString("recommendation_id"), 3.4, -1.2, Instant.parse("2026-10-16T12:00:00Z"))
        val compact = journal.compactHistory(Instant.parse("2026-10-17T12:00:00Z"))
        assertTrue(compact.last().accepted == true)
        assertEquals(3.4, compact.last().outcomePct ?: 0.0, 0.000001)
        assertEquals(8, compact.last().ageDays)
    }
    @Test
    fun acceptedDecisionIsIdempotentAndCannotReverse() {
        val folder = Files.createTempDirectory("investment-ai-decision-guard")
        val journal = AiRecommendationJournal(folder.resolve("journal.json").toString())
        val snapshot = AiAdvisorContract.buildSnapshot(
            allocations = listOf(
                AiAdvisorContract.Allocation("portfolio_asset", "BTC", 60.0, 60.0),
                AiAdvisorContract.Allocation("portfolio_asset", "GOLD-1", 40.0, 40.0)
            ),
            generatedAt = "2026-10-09T12:00:00Z"
        )
        val record = journal.recordRecommendation(snapshot, recommendation(), "manual")
        val id = record.getString("recommendation_id")
        journal.setDecision(id, "accepted")
        journal.setDecision(id, "accepted")
        org.junit.Assert.assertThrows(AiRecommendationJournal.JournalException::class.java) {
            journal.setDecision(id, "rejected")
        }
        assertEquals("accepted", journal.listRecords().single().getString("status"))
    }

    @Test
    fun windowsAndAndroidAiHistoriesRemainSeparate() {
        val folder = Files.createTempDirectory("investment-ai-scopes")
        val journal = AiRecommendationJournal(folder.resolve("journal.json").toString())
        fun snapshot(scope: String) = AiAdvisorContract.buildSnapshot(
            allocations = listOf(
                AiAdvisorContract.Allocation("portfolio_asset", "BTC", 60.0, 60.0),
                AiAdvisorContract.Allocation("portfolio_asset", "GOLD-1", 40.0, 40.0)
            ),
            generatedAt = "2026-10-09T12:00:00Z",
            portfolioScope = scope
        )
        val localId = journal.recordRecommendation(
            snapshot("android-local"), recommendation(), "manual",
            Instant.parse("2026-10-09T12:00:00Z")
        ).getString("recommendation_id")
        val windowsId = journal.recordRecommendation(
            snapshot("windows-core-readonly"), recommendation(), "manual",
            Instant.parse("2026-10-09T13:00:00Z")
        ).getString("recommendation_id")
        journal.setDecision(localId, "accepted")
        journal.setDecision(windowsId, "accepted")
        val time = Instant.parse("2026-10-10T12:00:00Z")
        assertEquals(localId,
            journal.compactHistory(now = time, portfolioScope = "android-local")
                .single().recommendationId)
        assertEquals(windowsId,
            journal.compactHistory(now = time, portfolioScope = "windows-core-readonly")
                .single().recommendationId)
        assertEquals(2, journal.compactHistory(now = time).size)
    }

    @Test
    fun pendingRecommendationIsNotLearningHistory() {
        val folder = Files.createTempDirectory("investment-ai-journal-pending")
        val journal = AiRecommendationJournal(folder.resolve("journal.json").toString())
        val snapshot = AiAdvisorContract.buildSnapshot(
            allocations = listOf(
                AiAdvisorContract.Allocation("portfolio_asset","BTC",100.0,100.0)
            ),
            generatedAt = "2026-10-09T12:00:00Z"
        )
        val rec = recommendation()
        rec.put(
            "suggested_targets",
            JSONArray().put(
                JSONObject()
                    .put("scope","portfolio_asset")
                    .put("public_key","BTC")
                    .put("current_pct",100)
                    .put("suggested_pct",100)
                    .put("reason","Hold")
            )
        )
        journal.recordRecommendation(
            snapshot, rec, "model-a", Instant.parse("2026-10-09T12:00:00Z")
        )
        assertTrue(
            journal.compactHistory(Instant.parse("2026-10-10T12:00:00Z")).isEmpty()
        )
    }

    @Test
    fun mergePreservesBothDeviceRecords() {
        val folder = Files.createTempDirectory("investment-ai-journal-merge")
        val journal = AiRecommendationJournal(folder.resolve("journal.json").toString())
        val remote = JSONObject()
            .put("format", AiRecommendationJournal.FORMAT)
            .put("schema_version", AiRecommendationJournal.SCHEMA_VERSION)
            .put(
                "records",
                JSONArray().put(
                    JSONObject()
                        .put("recommendation_id", "REC-REMOTE")
                        .put("created_at", "2026-10-09T11:00:00Z")
                        .put("status", "accepted")
                        .put("decision_at", "2026-10-09T12:00:00Z")
                )
            )
        val merged = journal.mergeDocument(remote)
        assertEquals(1, merged.getJSONArray("records").length())
        assertEquals(
            "REC-REMOTE",
            merged.getJSONArray("records").getJSONObject(0).getString("recommendation_id")
        )
    }

    @Test
    fun sameRecommendationIdCannotReplaceOriginalAdvice() {
        val folder = Files.createTempDirectory("investment-ai-journal-rewrite")
        val journal = AiRecommendationJournal(folder.resolve("journal.json").toString())
        val snapshot = AiAdvisorContract.buildSnapshot(
            allocations = listOf(
                AiAdvisorContract.Allocation("portfolio_asset", "BTC", 60.0, 60.0),
                AiAdvisorContract.Allocation("portfolio_asset", "GOLD-1", 40.0, 40.0)
            ),
            generatedAt = "2026-10-09T12:00:00Z"
        )
        journal.recordRecommendation(snapshot, recommendation(), "manual")
        val original = journal.exportDocument().toString()
        val conflict = JSONObject(original)
        conflict.getJSONArray("records").getJSONObject(0)
            .getJSONObject("recommendation").put("summary", "Different recommendation.")
        org.junit.Assert.assertThrows(AiRecommendationJournal.JournalException::class.java) {
            journal.mergeDocument(conflict)
        }
        assertEquals(original, journal.exportDocument().toString())
    }

    @Test
    fun oversizedImportNeverSilentlyDiscardsExistingJournal() {
        val folder = Files.createTempDirectory("investment-ai-journal-overflow")
        val journal = AiRecommendationJournal(folder.resolve("journal.json").toString())
        val records = JSONArray()
        for (i in 0..200) {
            records.put(JSONObject()
                .put("recommendation_id", "REC-$i")
                .put("created_at", "2026-10-09T12:00:00Z")
                .put("status", "pending"))
        }
        val remote = JSONObject()
            .put("format", AiRecommendationJournal.FORMAT)
            .put("schema_version", AiRecommendationJournal.SCHEMA_VERSION)
            .put("records", records)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            journal.mergeDocument(remote)
        }
        assertEquals(0, journal.listRecords().size)
    }

    @Test
    fun unknownImportedStatusCannotBeRecorded() {
        val folder = Files.createTempDirectory("investment-ai-journal-status")
        val journal = AiRecommendationJournal(folder.resolve("journal.json").toString())
        val remote = JSONObject()
            .put("format", AiRecommendationJournal.FORMAT)
            .put("schema_version", AiRecommendationJournal.SCHEMA_VERSION)
            .put("records", JSONArray().put(JSONObject()
                .put("recommendation_id", "REC-A")
                .put("created_at", "2026-10-09T12:00:00Z")
                .put("status", "applied")))
        org.junit.Assert.assertThrows(AiRecommendationJournal.JournalException::class.java) {
            journal.mergeDocument(remote)
        }
        assertEquals(0, journal.listRecords().size)
    }

    @Test(expected = AiRecommendationJournal.JournalException::class)
    fun conflictingTerminalDecisionsFailClosed() {
        val folder = Files.createTempDirectory("investment-ai-journal-conflict")
        val journal = AiRecommendationJournal(folder.resolve("journal.json").toString())
        val local = JSONObject()
            .put("format", AiRecommendationJournal.FORMAT)
            .put("schema_version", AiRecommendationJournal.SCHEMA_VERSION)
            .put(
                "records",
                JSONArray().put(
                    JSONObject()
                        .put("recommendation_id", "REC-X")
                        .put("created_at", "2026-10-09T12:00:00Z")
                        .put("status", "accepted")
                        .put("decision_at", "2026-10-09T13:00:00Z")
                )
            )
        journal.mergeDocument(local)
        journal.mergeDocument(
            JSONObject()
                .put("format", AiRecommendationJournal.FORMAT)
                .put("schema_version", AiRecommendationJournal.SCHEMA_VERSION)
                .put(
                    "records",
                    JSONArray().put(
                        JSONObject()
                            .put("recommendation_id", "REC-X")
                            .put("created_at", "2026-10-09T12:00:00Z")
                            .put("status", "rejected")
                            .put("decision_at", "2026-10-09T13:05:00Z")
                    )
                )
        )
    }

}
