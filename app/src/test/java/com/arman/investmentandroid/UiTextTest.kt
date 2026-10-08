package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    @Test fun persianAndArabicKeyboardNumbersAreAccepted() {
        assertEquals(1234.5, UiText.parseUserNumber("۱٬۲۳۴٫۵")!!, 0.00001)
        assertEquals(1234.5, UiText.parseUserNumber("١٬٢٣٤٫٥")!!, 0.00001)
        assertEquals(1234.5, UiText.parseUserNumber("1,234.5")!!, 0.00001)
    }

    @Test fun malformedNumbersAreRejected() {
        assertEquals(null, UiText.parseUserNumber("۱۲٫۳٫۴"))
        assertEquals(null, UiText.parseUserNumber("invalid"))
        assertEquals(null, UiText.parseUserNumber("1e999"))
    }

    @Test fun cloudErrorsAndProgressAreLocalized() {
        assertEquals("همگام‌سازی ابری ناموفق بود", UiText.translate("Cloud Sync Failed", "fa"))
        assertEquals("در حال همگام‌سازی ایمن", UiText.translate("Syncing safely", "fa"))
        assertEquals("Cloud Sync Failed", UiText.translate("Cloud Sync Failed", "en"))
    }

    @Test fun persianLabelsAreLocalized() {
        assertEquals("سبد سرمایه‌گذاری من", UiText.translate("My Portfolio", "fa"))
        assertEquals("My Portfolio", UiText.translate("My Portfolio", "en"))
    }

    @Test fun unknownUserAssetNamesAreNotChanged() {
        assertEquals("Custom BTC", UiText.translate("Custom BTC", "fa"))
    }

    @Test fun versionCaptionKeepsVersionNumber() {
        assertEquals(
            "سرمایه‌گذاری اندروید • v0.32.0",
            UiText.translate("Investment Android • v0.32.0", "fa")
        )
    }
}
