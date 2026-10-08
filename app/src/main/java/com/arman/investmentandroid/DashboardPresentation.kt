package com.arman.investmentandroid

/** Presentation only; does not touch investment holdings or cloud state. */
object DashboardPresentation {
    fun hasAssets(count: Int): Boolean = count > 0
    fun titleSizeSp(isPersian: Boolean): Float = if (isPersian) 22f else 25f
    fun summaryHeading(period: String, language: String): String =
        if (language == "fa") "خلاصه " + UiText.translate(period, "fa")
        else period + " Summary"
}
