package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortfolioSafetyTest {
    private fun asset(
        id: String = "android:crypto:btc",
        name: String = "Bitcoin",
        quantity: Double = 0.25,
        price: Double = 5_000_000_000.0
    ) = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("category", "Crypto")
        put("quantity", quantity)
        put("price_toman", price)
        put("average_cost_toman", 4_000_000_000.0)
        put("target_percent", 20.0)
        put("include_in_target", true)
        put("price_source", "Manual")
        put("symbol", "BTC")
    }

    private fun sharedDocument(
        schemaVersion: Int = 1,
        assets: JSONArray = JSONArray().put(asset())
    ) = JSONObject().apply {
        put("format", "investment.shared.portfolio")
        put("schemaVersion", schemaVersion)
        put("sharedPortfolio", JSONObject().apply {
            put("currency", "Toman")
            put("assets", assets)
            put("rebalance_tolerance_percent", 1.0)
        })
    }

    @Test
    fun validSharedPortfolioIsAccepted() {
        val validated = PortfolioSafety.validateBackup(sharedDocument().toString())

        assertEquals(PortfolioSafety.BackupKind.SHARED, validated.kind)
        assertEquals(1, validated.incomingAssetCount)
    }

    @Test
    fun malformedJsonIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.validateBackup("{not-json")
        }
    }

    @Test
    fun unsupportedFutureSchemaIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.validateBackup(sharedDocument(schemaVersion = 2).toString())
        }
    }

    @Test
    fun missingSharedAssetsIsRejected() {
        val document = sharedDocument()
        document.getJSONObject("sharedPortfolio").remove("assets")

        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.validateBackup(document.toString())
        }
    }

    @Test
    fun nonNumericMoneyIsRejectedInsteadOfBecomingZero() {
        val invalid = asset().put("price_toman", "unknown")

        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.validateBackup(
                sharedDocument(assets = JSONArray().put(invalid)).toString()
            )
        }
    }

    @Test
    fun duplicateSharedIdentityIsRejected() {
        val assets = JSONArray()
            .put(asset())
            .put(asset(name = "Duplicate Bitcoin"))

        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.validateBackup(sharedDocument(assets = assets).toString())
        }
    }

    @Test
    fun emptyIncomingPortfolioCannotReplaceNonEmptyLocalPortfolio() {
        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.ensureSafeReplacement(localAssetCount = 3, incomingAssetCount = 0)
        }
    }

    @Test
    fun emptyPortfolioIsAllowedWhenLocalPortfolioIsAlsoEmpty() {
        PortfolioSafety.ensureSafeReplacement(localAssetCount = 0, incomingAssetCount = 0)
    }

    @Test
    fun malformedAndroidSupplementDoesNotPassValidation() {
        val document = sharedDocument().put(
            "androidBackup",
            JSONObject().put("transactions", JSONArray().put("not-an-object"))
        )

        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.validateBackup(document.toString())
        }
    }

    @Test
    fun legacyBackupRequiresValidAssets() {
        val legacy = JSONObject()
            .put("backupVersion", 3)
            .put("assets", JSONArray().put(JSONObject().put("name", "Broken")))

        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.validateBackup(legacy.toString())
        }
    }

    @Test
    fun syncDecisionIsIdempotentWhenCopiesMatch() {
        assertEquals(
            PortfolioSafety.SyncDecision.MATCH,
            PortfolioSafety.decideSync("same", "same", "old")
        )
    }

    @Test
    fun syncDecisionLoadsOnlyRemoteOneSidedChange() {
        assertEquals(
            PortfolioSafety.SyncDecision.LOAD_REMOTE,
            PortfolioSafety.decideSync("base", "remote", "base")
        )
    }

    @Test
    fun syncDecisionUploadsOnlyLocalOneSidedChange() {
        assertEquals(
            PortfolioSafety.SyncDecision.UPLOAD_LOCAL,
            PortfolioSafety.decideSync("local", "base", "base")
        )
    }

    @Test
    fun syncDecisionNeverGuessesWhenBothCopiesChanged() {
        assertEquals(
            PortfolioSafety.SyncDecision.CONFLICT,
            PortfolioSafety.decideSync("local", "remote", "base")
        )
        assertEquals(
            PortfolioSafety.SyncDecision.FIRST_SYNC_CONFLICT,
            PortfolioSafety.decideSync("local", "remote", null)
        )
    }
}
