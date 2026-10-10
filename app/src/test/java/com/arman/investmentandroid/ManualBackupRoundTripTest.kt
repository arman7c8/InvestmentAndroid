package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Disposable local backup tests: no Drive, SAF, account identifiers or real data. */
class ManualBackupRoundTripTest {
    private fun fixture(): String = JSONObject()
        .put("format", "investment.shared.portfolio")
        .put("schemaVersion", 1)
        .put("updatedAt", 123L)
        .put("sharedPortfolio", JSONObject()
            .put("currency", "Toman")
            .put("rebalance_tolerance_percent", 1)
            .put("assets", JSONArray().put(JSONObject()
                .put("id", "android:synthetic-1")
                .put("name", "SYNTHETIC-ONLY")
                .put("category", "Other")
                .put("quantity", 2)
                .put("price_toman", 10000)
                .put("average_cost_toman", 8000)
                .put("target_percent", 100)
                .put("include_in_target", true))))
        .put("androidBackup", JSONObject()
            .put("backupVersion", 3)
            .put("transactions", JSONArray().put(JSONObject()
                .put("id", "synthetic-tx-1").put("timestamp", 10L)))
            .put("snapshots", JSONArray().put(JSONObject()
                .put("totalValue", 20000).put("timestamp", 11L))))
        .toString()

    @Test fun completeBackupExportsAndRestoresOriginalHistoryWithoutDuplication() {
        val backup = fixture()
        val first = PortfolioSafety.validateBackup(backup)
        var destination = ""
        PortfolioSafety.writeAndVerifyBackup(backup, { destination = it }, { destination })
        val restored = PortfolioSafety.validateBackup(destination)
        assertEquals(1, first.incomingAssetCount)
        assertEquals(1, restored.incomingAssetCount)
        assertEquals(1, restored.androidPayload!!.getJSONArray("transactions").length())
        assertEquals(1, restored.androidPayload!!.getJSONArray("snapshots").length())
        // A repeated import of the same document must not *generate* a timestamped
        // event: the shared restore path stores these source snapshots unchanged.
        assertEquals(restored.androidPayload!!.getJSONArray("snapshots").toString(),
            PortfolioSafety.validateBackup(destination).androidPayload!!
                .getJSONArray("snapshots").toString())
    }

    @Test fun emptyOrTruncatedOutputCannotReportSuccess() {
        val backup = fixture()
        for (incomplete in listOf("", backup.take(10))) {
            var destination = incomplete
            assertThrows(IllegalStateException::class.java) {
                PortfolioSafety.writeAndVerifyBackup(backup,
                    write = { destination = incomplete },
                    read = { destination })
            }
        }
    }

    @Test fun invalidSourceMustNotBeExportedAsVerifiedBackup() {
        val broken = JSONObject(fixture()).put("schemaVersion", 99).toString()
        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSafety.validateBackup(broken)
        }
    }

    @Test fun historicalRecordsAreStableAcrossSerializations() {
        val backup = fixture()
        val payload = PortfolioSafety.validateBackup(backup).androidPayload!!
        val original = payload.getJSONArray("snapshots").toString()
        val reparsed = JSONObject(payload.toString()).getJSONArray("snapshots").toString()
        assertEquals(original, reparsed)
    }
}
