package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreProjectionBoundaryTest {
    private fun windowsOrigin(source: JSONObject): JSONObject = JSONObject()
        .put("id", "local:btc")
        .put("name", "Synthetic BTC")
        .put("category", "Crypto")
        .put("source_platform", "android")
        .put("source", source)

    @Test
    fun windowsProjectionCannotBeRelabeledAsAndroidAsset() {
        val source = JSONObject()
            .put("kind", "asset")
            .put("group_id", "crypto")
            .put("asset_id", "btc")
        val portfolio = JSONObject()
            .put("assets", JSONArray().put(windowsOrigin(source)))

        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.requireEditableAndroidPortfolio(portfolio)
        }
    }

    @Test
    fun nestedWindowsPlatformCannotBeHiddenByAndroidTopLevelOrigin() {
        for (key in listOf("platform", "source_platform", "sourcePlatform")) {
            val portfolio = JSONObject().put("assets", JSONArray().put(
                windowsOrigin(JSONObject().put(key, " WINDOWS-CORE "))
            ))
            assertThrows(IllegalArgumentException::class.java) {
                PortfolioSafety.requireEditableAndroidPortfolio(portfolio)
            }
        }
    }

    @Test
    fun partialCoreLikeMetadataDoesNotBlockOrdinaryAndroidAsset() {
        val source = JSONObject()
            .put("kind", "asset")
            .put("group_id", "crypto")
        PortfolioSafety.requireEditableAndroidPortfolio(
            JSONObject().put("assets", JSONArray().put(windowsOrigin(source)))
        )
        assertFalse(CoreProjectionBoundary.isWindowsCoreAsset(
            listOf("android"), listOf("local:btc"), emptyList(), "asset", "crypto", ""
        ))
    }

    @Test
    fun AndroidNativeMetadataWithNonCoreKindRemainsEditable() {
        val asset = windowsOrigin(JSONObject()
            .put("kind", "manual")
            .put("group_id", "crypto")
            .put("asset_id", "btc"))
        PortfolioSafety.requireEditableAndroidPortfolio(
            JSONObject().put("assets", JSONArray().put(asset))
        )
        assertFalse(CoreProjectionBoundary.isWindowsCoreAsset(
            listOf("android"), listOf("local:btc"), emptyList(), "manual", "crypto", "btc"
        ))
    }

    @Test
    fun windowsCoreSourceSignatureIsIndependentFromAssetId() {
        assertTrue(CoreProjectionBoundary.isWindowsCoreAsset(
            listOf("android"), listOf("local:btc"), emptyList(), "asset", "crypto", "btc"
        ))
        assertTrue(CoreProjectionBoundary.isWindowsCoreAsset(
            emptyList(), listOf("windows:crypto:btc"), emptyList(), "", "", ""
        ))
    }
}
