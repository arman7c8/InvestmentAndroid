package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAdvisorContractTest {
    @Test
    fun snapshotContainsPercentagesAndNoMoneyOrQuantity() {
        val payload = AiAdvisorContract.buildSnapshot(
            allocations = listOf(
                AiAdvisorContract.Allocation("group", "crypto", 31.2, 28.0),
                AiAdvisorContract.Allocation("asset", "btc", 45.0, 50.0, "crypto", 8.4)
            ),
            portfolioPerformancePct = mapOf("7d" to 2.1, "30d" to -1.7),
            generatedAt = "2026-10-09T12:00:00Z"
        )
        AiAdvisorContract.assertSnapshotSafe(payload)
        val raw = payload.toString().lowercase()
        listOf("toman", "rial", "quantity", "price", "balance", "account", "bank")
            .forEach { forbidden -> assertFalse(raw.contains(forbidden)) }
        assertEquals(31.2, payload.getJSONArray("allocations").getJSONObject(0).getDouble("current_pct"), 0.000001)
        assertEquals(-1.7, payload.getJSONObject("portfolio_performance_pct").getDouble("30d"), 0.000001)
    }

    @Test
    fun blankPrivateDisplayKeyFallsBackToAnonymousRowKey() {
        val payload = AiAdvisorContract.buildSnapshot(
            allocations = listOf(
                AiAdvisorContract.Allocation("asset", "", 3.0, 5.0, "other")
            ),
            generatedAt = "2026-10-09T12:00:00Z"
        )
        val row = payload.getJSONArray("allocations").getJSONObject(0)
        assertEquals("ROW-1", row.getString("public_key"))
        assertFalse(row.has("name"))
    }

    @Test
    fun atlasContextContainsOnlyCompactPublicSignals() {
        val payload = AiAdvisorContract.buildSnapshot(
            allocations = listOf(AiAdvisorContract.Allocation("group", "crypto", 30.0, 30.0)),
            atlasContext = AiAdvisorContract.AtlasContext(
                "2026-10-09T12:00:00Z",
                "high",
                "Liquidity risk increased.",
                listOf(AiAdvisorContract.AtlasSignal("BTC", "risk", 82.0, "Volatility"))
            ),
            generatedAt = "2026-10-09T12:00:00Z"
        )
        val atlas = payload.getJSONObject("atlas_context")
        assertEquals("high", atlas.getString("risk_regime"))
        assertEquals("BTC", atlas.getJSONArray("signals").getJSONObject(0).getString("public_key"))
    }

    @Test
    fun privacyFlagsAreAlwaysFalse() {
        val payload = AiAdvisorContract.buildSnapshot(
            allocations = emptyList(),
            generatedAt = "2026-10-09T12:00:00Z"
        )
        val privacy = payload.getJSONObject("privacy")
        assertTrue(!privacy.getBoolean("money_included"))
        assertTrue(!privacy.getBoolean("units_included"))
        assertTrue(!privacy.getBoolean("financial_ids_included"))
        assertTrue(!privacy.getBoolean("personal_identity_included"))
    }

    @Test(expected = AiAdvisorContract.ContractException::class)
    fun invalidPercentageFailsClosed() {
        AiAdvisorContract.buildSnapshot(
            allocations = listOf(AiAdvisorContract.Allocation("group", "gold", Double.NaN, 25.0)),
            generatedAt = "2026-10-09T12:00:00Z"
        )
    }
}
