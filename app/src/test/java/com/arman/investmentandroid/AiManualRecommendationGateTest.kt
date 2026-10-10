package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class AiManualRecommendationGateTest {
    private fun snapshot() = AiAdvisorContract.buildSnapshot(
        allocations = listOf(
            AiAdvisorContract.Allocation("portfolio_asset", "BTC", 65.0, 60.0),
            AiAdvisorContract.Allocation("portfolio_asset", "AYAR", 35.0, 40.0)
        ), generatedAt = "2026-10-10T09:00:00Z"
    )

    private fun suggestion() = JSONObject()
        .put("format", "investment.ai.recommendation")
        .put("schema_version", 1)
        .put("summary", "Review diversification.")
        .put("market_risk", "medium")
        .put("portfolio_risk", "medium")
        .put("confidence_pct", 75)
        .put("suggested_targets", JSONArray()
            .put(JSONObject().put("scope", "portfolio_asset")
                .put("public_key", "BTC").put("current_pct", 65)
                .put("suggested_pct", 55).put("reason", "Reduce concentration"))
            .put(JSONObject().put("scope", "portfolio_asset")
                .put("public_key", "AYAR").put("current_pct", 35)
                .put("suggested_pct", 45).put("reason", "Diversification")))
        .put("warnings", JSONArray())
        .put("review_after_days", 7)

    @Test fun matchingPercentagesAreEligibleOnlyForManualTracking() {
        AiManualRecommendationGate.validateAgainstSnapshot(snapshot(), suggestion())
    }

    @Test fun stalePortfolioSuggestionIsRejected() {
        val bad = suggestion()
        bad.getJSONArray("suggested_targets").getJSONObject(0)
            .put("current_pct", 30)
        assertThrows(IllegalArgumentException::class.java) {
            AiManualRecommendationGate.validateAgainstSnapshot(snapshot(), bad)
        }
    }

    @Test fun duplicateTargetsCannotMasqueradeAsCompleteCoverage() {
        val bad = suggestion()
        bad.getJSONArray("suggested_targets").getJSONObject(1)
            .put("public_key", "BTC")
        assertThrows(IllegalArgumentException::class.java) {
            AiManualRecommendationGate.validateAgainstSnapshot(snapshot(), bad)
        }
    }

    @Test fun unknownAssetIsRejected() {
        val bad = suggestion()
        bad.getJSONArray("suggested_targets").getJSONObject(1)
            .put("public_key", "BANK-ACCOUNT-123")
        assertThrows(IllegalArgumentException::class.java) {
            AiManualRecommendationGate.validateAgainstSnapshot(snapshot(), bad)
        }
    }
}
