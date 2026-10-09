package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AiAdvisorRecommendationTest {
    private fun valid(): JSONObject =
        JSONObject()
            .put("format", "investment.ai.recommendation")
            .put("schema_version", 1)
            .put("summary", "Reduce concentration.")
            .put("market_risk", "high")
            .put("portfolio_risk", "medium")
            .put("confidence_pct", 72)
            .put(
                "suggested_targets",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("scope", "portfolio_asset")
                            .put("public_key", "BTC")
                            .put("current_pct", 60)
                            .put("suggested_pct", 55)
                            .put("reason", "Reduce concentration.")
                    )
                    .put(
                        JSONObject()
                            .put("scope", "portfolio_asset")
                            .put("public_key", "GOLD-1")
                            .put("current_pct", 40)
                            .put("suggested_pct", 45)
                            .put("reason", "Diversify.")
                    )
            )
            .put("warnings", JSONArray())
            .put("review_after_days", 7)

    @Test
    fun validFlatPortfolioRecommendationPasses() {
        val value = valid()
        AiAdvisorRecommendation.validate(value)
        assertEquals(72.0, value.getDouble("confidence_pct"), 0.0)
    }

    @Test(expected = AiAdvisorRecommendation.RecommendationException::class)
    fun moneyInstructionIsRejected() {
        val value = valid()
        value.getJSONArray("suggested_targets")
            .getJSONObject(0)
            .put("buy_amount_toman", 100)
        AiAdvisorRecommendation.validate(value)
    }

    @Test(expected = AiAdvisorRecommendation.RecommendationException::class)
    fun flatTargetsMustSumToOneHundred() {
        val value = valid()
        value.getJSONArray("suggested_targets")
            .getJSONObject(0)
            .put("suggested_pct", 70)
        AiAdvisorRecommendation.validate(value)
    }
}
