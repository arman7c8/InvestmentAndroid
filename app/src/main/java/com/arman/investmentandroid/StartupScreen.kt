package com.arman.investmentandroid

/** Cold-start directly to dashboard unless app PIN or restored price center requires otherwise. */
object StartupScreen {
    enum class Destination { LOCKED, PORTFOLIO, PRICE_CENTER }

    fun destination(lockEnabled: Boolean, priceCenterRestored: Boolean): Destination =
        when {
            lockEnabled -> Destination.LOCKED
            priceCenterRestored -> Destination.PRICE_CENTER
            else -> Destination.PORTFOLIO
        }
}
