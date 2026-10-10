package com.arman.investmentandroid

/** Correction allowed only for the latest verified BUY, without replaying later trades. */
object BuyCorrection {
    data class Calculation(val quantity: Double, val averageCost: Double, val currentPrice: Double)
    fun calculate(previousQuantity: Double, previousAverageCost: Double,
                  newBuyQuantity: Double, newBuyPrice: Double): Calculation {
        require(previousQuantity.isFinite() && previousQuantity >= 0.0)
        require(previousAverageCost.isFinite() && previousAverageCost >= 0.0)
        require(newBuyQuantity.isFinite() && newBuyQuantity > 0.0)
        require(newBuyPrice.isFinite() && newBuyPrice >= 0.0)
        val quantity = previousQuantity + newBuyQuantity
        val invested = previousQuantity * previousAverageCost + newBuyQuantity * newBuyPrice
        require(quantity.isFinite() && invested.isFinite() && quantity > 0.0)
        val average = invested / quantity
        require(average.isFinite() && average >= 0.0)
        return Calculation(quantity, average, newBuyPrice)
    }
}
