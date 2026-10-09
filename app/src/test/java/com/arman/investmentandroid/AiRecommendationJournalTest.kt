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

}
