package com.arman.investmentandroid

/** Read-only trend geometry; never interpolates fictitious financial snapshots. */
object PortfolioTrend {
    data class Sample(val timestamp: Long, val value: Double)
    data class Point(val x: Float, val y: Float)

    fun points(samples: List<Sample>): List<Point> {
        val ordered = samples.filter { it.timestamp >= 0 && it.value.isFinite() && it.value >= 0.0 }
            .sortedBy { it.timestamp }
        if (ordered.isEmpty()) return emptyList()
        if (ordered.size == 1) return listOf(Point(0.5f, 0.5f))
        val minTime = ordered.first().timestamp
        val maxTime = ordered.last().timestamp
        val minValue = ordered.minOf { it.value }
        val maxValue = ordered.maxOf { it.value }
        val timeSpan = maxTime.toDouble() - minTime.toDouble()
        val valueSpan = maxValue - minValue
        return ordered.mapIndexed { index, sample ->
            val x = if (timeSpan > 0.0) {
                ((sample.timestamp.toDouble() - minTime.toDouble()) / timeSpan).toFloat()
            } else {
                index.toFloat() / (ordered.size - 1).toFloat()
            }
            val y = if (valueSpan > 0.0 && valueSpan.isFinite()) {
                ((sample.value - minValue) / valueSpan).toFloat()
            } else {
                0.5f
            }
            Point(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }
    }
}
