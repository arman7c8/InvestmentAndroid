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
    fun completedProviderWriteMustReadBackExactly() {
        var stored = "old"
        PortfolioSafety.writeAndVerifyBackup("new backup", { stored = it }, { stored })
        assertEquals("new backup", stored)
    }

    @Test
    fun shortProviderWriteIsNotAccepted() {
        var stored = "old"
        assertThrows(IllegalStateException::class.java) {
            PortfolioSafety.writeAndVerifyBackup("complete backup",
                write = { stored = it.take(4) }, read = { stored })
        }
    }

    @Test
    fun staleProviderReadIsNotAccepted() {
        var stored = "previous valid backup"
        assertThrows(IllegalStateException::class.java) {
            PortfolioSafety.writeAndVerifyBackup("new complete backup",
                write = { _ -> Unit }, read = { stored })
        }
        assertEquals("previous valid backup", stored)
    }

    @Test
    fun providerWriteFailureIsNotReportedAsSuccess() {
        assertThrows(IllegalStateException::class.java) {
            PortfolioSafety.writeAndVerifyBackup("backup",
                write = { throw IllegalStateException("provider failed") }, read = { "backup" })
        }
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
    fun overflowingAssetValueIsRejected() {
        val invalid = asset(quantity = Double.MAX_VALUE, price = 10.0)
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

    @Test
    fun cloudRestoreKeepsLocalTransactionsMissingFromOlderCloudSupplement() {
        val remote = JSONArray().put(JSONObject().put("id", "old").put("timestamp", 1L))
        val local = JSONArray().put(JSONObject().put("id", "new").put("timestamp", 2L))
        val merged = PortfolioSafety.mergeHistory(local, remote, "id")

        assertEquals(2, merged.length())
        assertEquals("old", merged.getJSONObject(0).getString("id"))
        assertEquals("new", merged.getJSONObject(1).getString("id"))
    }

    @Test
    fun cloudRestoreRejectsConflictingTransactionWithoutDiscardingEitherRecord() {
        val remote = JSONArray().put(JSONObject().put("id", "same").put("timestamp", 1L))
        val local = JSONArray().put(JSONObject().put("id", "same").put("timestamp", 2L))
        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.mergeHistory(local, remote, "id")
        }
        assertEquals(1L, remote.getJSONObject(0).getLong("timestamp"))
        assertEquals(2L, local.getJSONObject(0).getLong("timestamp"))
    }

    @Test
    fun identicalRecordsWithDifferentJsonKeyOrderMergeOnce() {
        val remote = JSONArray().put(JSONObject().put("id", "same").put("timestamp", 1L))
        val local = JSONArray().put(JSONObject().put("timestamp", 1L).put("id", "same"))
        assertEquals(1, PortfolioSafety.mergeHistory(local, remote, "id").length())
    }

    @Test
    fun conflictingSnapshotAtSameTimestampIsNotSilentlyReplaced() {
        val remote = JSONArray().put(JSONObject().put("timestamp", 10L).put("totalValue", 100))
        val local = JSONArray().put(JSONObject().put("timestamp", 10L).put("totalValue", 250))
        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.mergeHistory(local, remote, "timestamp")
        }
    }

    @Test
    fun newSupplementPreservesUnrecognizedRemoteFields() {
        val local = JSONObject().put("transactions", JSONArray()).put("backupVersion", 3)
        val remote = JSONObject().put("customFutureField", JSONObject().put("keep", "yes"))
            .put("backupVersion", 2)
        val merged = PortfolioSafety.preserveSupplementalFields(local, remote)
        assertEquals("yes", merged.getJSONObject("customFutureField").getString("keep"))
        assertEquals(3, merged.getInt("backupVersion"))
    }

    @Test
    fun legacyWindowsSqliteIsRejectedBeforeJsonParsing() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.validateBackup("SQLite format 3\\u0000fake core file")
        }
        org.junit.Assert.assertTrue(error.message!!.contains("Windows Core SQLite"))
    }

    @Test
    fun embeddedCoreLedgerIsRejectedRatherThanDroppingItsHistory() {
        val document = sharedDocument().put("coreLedger", JSONObject().put("transactions", JSONArray()))
        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.validateBackup(document.toString())
        }
    }

    @Test
    fun cashImportMustNotDiscardUnitQuantity() {
        PortfolioSafety.requireSafeCashQuantity("Cash", 1.0)
        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.requireSafeCashQuantity("Cash", 50.0)
        }
        PortfolioSafety.requireSafeCashQuantity("Crypto", 50.0)
    }

    @Test
    fun cloudRestorePreservesHistoryBeyondOneHundredRows() {
        val local = JSONArray()
        for (index in 0 until 250) {
            local.put(JSONObject().put("id", "local-$index").put("timestamp", index))
        }
        val remote = JSONArray().put(JSONObject().put("id", "remote").put("timestamp", 300))

        assertEquals(251, PortfolioSafety.mergeHistory(local, remote, "id").length())
    }
}
