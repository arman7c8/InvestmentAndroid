package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class WindowsCoreHomeTest {
    /** Pure fixture with the same 11 required Core tables as Windows v0.12. */
    private fun exportedSnapshot(quantity: Double = 1.0, value: Double? = 5_000_000.0): String {
        val tables = JSONObject()
        val names = listOf(
            "schema_meta", "imported_snapshots", "opening_accounts",
            "opening_positions", "assets", "portfolio_groups",
            "transactions", "prices", "quantity_corrections",
            "transaction_revisions", "transaction_voids"
        )
        for (name in names) {
            var columns = JSONArray().put("id")
            val rows = JSONArray()
            val rowids = JSONArray()
            when (name) {
                "imported_snapshots" -> {
                    rows.put(JSONArray().put(1))
                    rowids.put(1)
                }
                "assets" -> {
                    rows.put(JSONArray().put("btc"))
                    rowids.put(1)
                }
                "opening_positions" -> {
                    columns = JSONArray().put("snapshot_id").put("asset_id").put("quantity")
                    rows.put(JSONArray().put(1).put("btc").put(quantity))
                    rowids.put(1)
                }
                "opening_accounts" -> {
                    columns = JSONArray().put("snapshot_id").put("account_id").put("balance_toman")
                    rows.put(JSONArray().put(1).put("wallet").put(1_500_000.0))
                    rowids.put(1)
                }
            }
            tables.put(name, JSONObject().put("columns", columns)
                .put("rows", rows).put("rowids", rowids))
        }

        val holding = JSONObject()
            .put("id", "btc").put("name", "Bitcoin").put("quantity", quantity)
            .put("value_toman", value ?: JSONObject.NULL)
        val cash = JSONObject().put("id", "wallet").put("balance_toman", 1_500_000.0)
        val preview = JSONObject()
            .put("holdings", JSONArray().put(holding))
            .put("cashAccounts", JSONArray().put(cash))
            .put("transactionCount", 0)
            .put("activeTransactionCount", 0)
            .put("correctionCount", 0)
            .put("revisionCount", 0)
            .put("voidCount", 0)
        val payload = JSONObject()
            .put("source", JSONObject().put("platform", "windows-core").put("schemaVersion", 11))
            .put("tables", tables)
            .put("preview", preview)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return JSONObject()
            .put("format", "investment.core.readonly")
            .put("contractVersion", 1)
            .put("encoding", "base64-json-utf8")
            .put("sha256", sha)
            .put("payloadBase64", Base64.getEncoder().encodeToString(bytes))
            .toString()
    }

    @Test fun verifiedWindowsHomeValuesAreIndependentOfAndroidAssets() {
        val overview = WindowsCoreHome.inspect(exportedSnapshot())
        assertEquals(11, overview.schema)
        assertEquals(1, overview.holdings.size)
        assertEquals("btc", overview.holdings[0].id)
        assertEquals("Bitcoin", overview.holdings[0].name)
        assertEquals(1.0, overview.holdings[0].quantity, 0.0)
        assertEquals(5_000_000.0, overview.holdings[0].valueToman!!, 0.0)
        assertEquals(1_500_000.0, overview.cashToman, 0.0)
        assertEquals(6_500_000.0, overview.completeValueToman!!, 0.0)
        assertEquals(0, overview.missingPriceCount)
        assertEquals(0, overview.transactionCount)
    }

    @Test fun missingNonzeroQuoteIsExplicitlyIncomplete() {
        val overview = WindowsCoreHome.inspect(exportedSnapshot(value = null))
        assertEquals(1, overview.missingPriceCount)
        assertNull(overview.completeValueToman)
        assertEquals(1_500_000.0, overview.partialValueToman, 0.0)
    }

    @Test fun zeroQuantityWithMissingPriceDoesNotInvalidateTotal() {
        val overview = WindowsCoreHome.inspect(exportedSnapshot(quantity = 0.0, value = null))
        assertEquals(0, overview.missingPriceCount)
        assertEquals(1_500_000.0, overview.completeValueToman!!, 0.0)
    }

    @Test fun changedChecksumOrForgedHoldingsNeverReachHome() {
        val valid = JSONObject(exportedSnapshot())
        valid.put("sha256", "0".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            WindowsCoreHome.inspect(valid.toString())
        }
        val raw = JSONObject(exportedSnapshot())
        val payload = JSONObject(String(Base64.getDecoder().decode(raw.getString("payloadBase64"))))
        payload.getJSONObject("preview").getJSONArray("holdings")
            .getJSONObject(0).put("quantity", 99.0)
        val changed = payload.toString().toByteArray(Charsets.UTF_8)
        raw.put("payloadBase64", Base64.getEncoder().encodeToString(changed))
        raw.put("sha256", MessageDigest.getInstance("SHA-256").digest(changed)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) })
        assertThrows(IllegalArgumentException::class.java) {
            WindowsCoreHome.inspect(raw.toString())
        }
    }

    @Test fun overviewDoesNotPretendToBeLiveOrEditable() {
        val overview = WindowsCoreHome.inspect(exportedSnapshot())
        assertFalse(overview.sha256.isBlank())
        assertTrue(overview.completeValueToman != null)
        // No methods exist to apply trades, change Android preferences or sync.
    }
}
