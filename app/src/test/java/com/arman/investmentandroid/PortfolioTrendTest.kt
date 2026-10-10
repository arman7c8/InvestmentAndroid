package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioTrendTest {
    @Test fun noSyntheticPointsOnEmptyHistory() {
        assertTrue(PortfolioTrend.points(emptyList()).isEmpty())
    }
    @Test fun singleSnapshotIsNotInventedAsGrowth() {
        assertEquals(listOf(PortfolioTrend.Point(0.5f, 0.5f)),
            PortfolioTrend.points(listOf(PortfolioTrend.Sample(1, 100.0))))
    }
    @Test fun chronologicalOrderingAndRange() {
        val points = PortfolioTrend.points(listOf(
            PortfolioTrend.Sample(300, 300.0),
            PortfolioTrend.Sample(100, 100.0),
            PortfolioTrend.Sample(200, 200.0)))
        assertEquals(listOf(0f, 0.5f, 1f), points.map { it.x })
        assertEquals(listOf(0f, 0.5f, 1f), points.map { it.y })
    }
    @Test fun constantValueIsFlatAndEqualTimestampsAreSpread() {
        val p = PortfolioTrend.points(listOf(
            PortfolioTrend.Sample(5, 20.0), PortfolioTrend.Sample(5, 20.0)))
        assertEquals(listOf(0f, 1f), p.map { it.x })
        assertEquals(listOf(0.5f, 0.5f), p.map { it.y })
    }
    @Test fun invalidValuesAreNotPlotted() {
        val p = PortfolioTrend.points(listOf(
            PortfolioTrend.Sample(1, Double.NaN),
            PortfolioTrend.Sample(2, Double.POSITIVE_INFINITY),
            PortfolioTrend.Sample(3, -1.0),
            PortfolioTrend.Sample(4, 10.0)))
        assertEquals(1, p.size)
    }
}
