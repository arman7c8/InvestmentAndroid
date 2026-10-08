package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class CoreSnapshotPreviewTest {
    private fun sample(): JSONObject {
        val names = listOf("schema_meta", "imported_snapshots", "opening_accounts",
            "opening_positions", "assets", "portfolio_groups", "transactions", "prices",
            "quantity_corrections", "transaction_revisions", "transaction_voids")
        val tables = JSONObject()
        for (name in names) {
            val rows = JSONArray()
            val ids = JSONArray()
            if (name == "assets") { rows.put(JSONArray().put("btc")); ids.put(1) }
            tables.put(name, JSONObject().put("columns", JSONArray().put("id"))
                .put("rowids", ids).put("rows", rows))
        }
        val holding = JSONObject().put("id", "btc").put("name", "Bitcoin")
            .put("quantity", 1.0).put("value_toman", 5_000_000.0)
        val projection = JSONObject().put("holdings", JSONArray().put(holding))
            .put("cashAccounts", JSONArray()).put("transactionCount", 0)
            .put("correctionCount", 0).put("revisionCount", 0).put("voidCount", 0)
        val payload = JSONObject().put("source", JSONObject()
            .put("platform", "windows-core").put("schemaVersion", 11))
            .put("tables", tables).put("preview", projection)
        return wrap(payload)
    }
    private fun wrap(payload: JSONObject): JSONObject {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return JSONObject().put("format", "investment.core.readonly")
            .put("contractVersion", 1).put("encoding", "base64-json-utf8")
            .put("sha256", checksum).put("payloadBase64", Base64.getEncoder().encodeToString(bytes))
    }
    @Test fun validSnapshotCanBePreviewedWithoutImporting() {
        val preview = CoreSnapshotPreview.inspect(sample().toString())
        assertEquals(11, preview.schema)
        assertEquals(11, preview.tableCount)
        assertEquals(1, preview.holdings.size)
    }
    @Test fun changedChecksumIsRejected() {
        val bad = sample().put("sha256", "0".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { CoreSnapshotPreview.inspect(bad.toString()) }
    }
    @Test fun missingLedgerTableIsRejectedEvenWithValidChecksum() {
        val sample = sample()
        val raw = String(Base64.getDecoder().decode(sample.getString("payloadBase64")))
        val payload = JSONObject(raw)
        payload.getJSONObject("tables").remove("transaction_voids")
        assertThrows(IllegalArgumentException::class.java) {
            CoreSnapshotPreview.inspect(wrap(payload).toString())
        }
    }
    @Test fun AndroidJsonIsNotAcceptedAsWindowsCore() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreSnapshotPreview.inspect("""{"format":"investment.shared.portfolio","schemaVersion":1}""")
        }
    }
}
