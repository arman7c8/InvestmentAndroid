package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    @Test fun persianLabelsAreLocalized() {
        assertEquals("سبد سرمایه‌گذاری من", UiText.translate("My Portfolio", "fa"))
        assertEquals("My Portfolio", UiText.translate("My Portfolio", "en"))
    }

    @Test fun unknownUserAssetNamesAreNotChanged() {
        assertEquals("Custom BTC", UiText.translate("Custom BTC", "fa"))
    }
}
