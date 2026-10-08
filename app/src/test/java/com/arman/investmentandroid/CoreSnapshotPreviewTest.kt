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
            var columns = JSONArray().put("id")
            if (name == "assets") { rows.put(JSONArray().put("btc")); ids.put(1) }
            if (name == "imported_snapshots") { rows.put(JSONArray().put(1)); ids.put(1) }
            if (name == "opening_positions") {
                columns = JSONArray().put("snapshot_id").put("asset_id").put("quantity")
                rows.put(JSONArray().put(1).put("btc").put(1.0)); ids.put(1)
            }
            if (name == "opening_accounts") {
                columns = JSONArray().put("snapshot_id").put("account_id").put("balance_toman")
                rows.put(JSONArray().put(1).put("wallet").put(1_500_000.0)); ids.put(1)
            }
            tables.put(name, JSONObject().put("columns", columns)
                .put("rowids", ids).put("rows", rows))
        }
        val holding = JSONObject().put("id", "btc").put("name", "Bitcoin")
            .put("quantity", 1.0).put("value_toman", 5_000_000.0)
        val projection = JSONObject().put("holdings", JSONArray().put(holding))
            .put("cashAccounts", JSONArray().put(JSONObject()
                .put("id", "wallet").put("balance_toman", 1_500_000.0)))
            .put("transactionCount", 0).put("activeTransactionCount", 0)
            .put("correctionCount", 0).put("revisionCount", 0).put("voidCount", 0)
        val payload = JSONObject().put("source", JSONObject()
            .put("platform", "windows-core").put("schemaVersion", 11))
            .put("tables", tables).put("preview", projection)
        return wrap(payload)
    }
    private fun wrap(payload: JSONObject, version: Int = 1): JSONObject {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return JSONObject().put("format", "investment.core.readonly")
            .put("contractVersion", version).put("encoding", "base64-json-utf8")
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
    @Test fun validChecksumButWrongHoldingQuantityIsRejected() {
        val sample = sample()
        val body = JSONObject(String(Base64.getDecoder().decode(sample.getString("payloadBase64"))))
        body.getJSONObject("preview").getJSONArray("holdings")
            .getJSONObject(0).put("quantity", 10.0)
        assertThrows(IllegalArgumentException::class.java) {
            CoreSnapshotPreview.inspect(wrap(body).toString())
        }
    }

    @Test fun validChecksumButWrongCashBalanceIsRejected() {
        val sample = sample()
        val body = JSONObject(String(Base64.getDecoder().decode(sample.getString("payloadBase64"))))
        body.getJSONObject("preview").getJSONArray("cashAccounts")
            .getJSONObject(0).put("balance_toman", 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            CoreSnapshotPreview.inspect(wrap(body).toString())
        }
    }

    @Test fun tradeAndCorrectionWithVoidedSaleRecomputeToWindowsTotals() {
        val envelope = sample()
        val body = JSONObject(String(Base64.getDecoder().decode(envelope.getString("payloadBase64"))))
        val tables = body.getJSONObject("tables")
        tables.getJSONObject("transactions")
            .put("columns", JSONArray().put("id").put("type").put("amount_toman")
                .put("asset_id").put("quantity").put("source").put("destination"))
            .put("rowids", JSONArray().put(1).put(2))
            .put("rows", JSONArray()
                .put(JSONArray().put("buy-1").put("buy").put(200_000.0)
                    .put("btc").put(0.25).put("wallet").put(JSONObject.NULL))
                .put(JSONArray().put("sell-2").put("sell").put(100_000.0)
                    .put("btc").put(0.05).put(JSONObject.NULL).put("wallet")))
        tables.getJSONObject("transaction_voids")
            .put("columns", JSONArray().put("transaction_id"))
            .put("rowids", JSONArray().put(1))
            .put("rows", JSONArray().put(JSONArray().put("sell-2")))
        tables.getJSONObject("quantity_corrections")
            .put("columns", JSONArray().put("asset_id").put("delta"))
            .put("rowids", JSONArray().put(1))
            .put("rows", JSONArray().put(JSONArray().put("btc").put(0.10)))
        val preview = body.getJSONObject("preview")
        preview.put("transactionCount", 2).put("activeTransactionCount", 1)
            .put("correctionCount", 1).put("voidCount", 1)
        preview.getJSONArray("holdings").getJSONObject(0).put("quantity", 1.35)
        preview.getJSONArray("cashAccounts").getJSONObject(0)
            .put("balance_toman", 1_300_000.0)

        val inspected = CoreSnapshotPreview.inspect(wrap(body).toString())
        assertEquals(2, inspected.transactions)
        assertEquals(1, inspected.voids)
        assertEquals(1, inspected.corrections)

        preview.getJSONArray("cashAccounts").getJSONObject(0)
            .put("balance_toman", 1_400_000.0)
        assertThrows(IllegalArgumentException::class.java) {
            CoreSnapshotPreview.inspect(wrap(body).toString())
        }
    }

    @Test fun unknownCoreTransactionTypeFailsClosed() {
        val envelope = sample()
        val body = JSONObject(String(Base64.getDecoder().decode(envelope.getString("payloadBase64"))))
        body.getJSONObject("tables").getJSONObject("transactions")
            .put("columns", JSONArray().put("id").put("type").put("amount_toman"))
            .put("rowids", JSONArray().put(1))
            .put("rows", JSONArray().put(JSONArray().put("unknown-1").put("trade-magic").put(100.0)))
        body.getJSONObject("preview").put("transactionCount", 1).put("activeTransactionCount", 1)
        assertThrows(IllegalArgumentException::class.java) {
            CoreSnapshotPreview.inspect(wrap(body).toString())
        }
    }


    private fun syntheticV2(): JSONObject {
        val base = sample()
        val payload = JSONObject(String(Base64.getDecoder().decode(base.getString("payloadBase64"))))
        val tables = payload.getJSONObject("tables")
        tables.getJSONObject("portfolio_groups")
            .put("columns", JSONArray().put("id").put("name").put("target_pct")
                .put("sort_order").put("pricing_currency"))
            .put("rowids", JSONArray().put(1))
            .put("rows", JSONArray().put(JSONArray().put("crypto")
                .put("Digital Assets").put(40.0).put(2).put("USDT")))
        tables.getJSONObject("assets")
            .put("columns", JSONArray().put("id").put("name").put("category")
                .put("target_pct").put("currency"))
            .put("rows", JSONArray().put(JSONArray().put("btc").put("Bitcoin")
                .put("crypto").put(25.0).put("USDT")))
        tables.put("portfolio_settings", JSONObject()
            .put("columns", JSONArray().put("key").put("value"))
            .put("rowids", JSONArray().put(1).put(2).put(3))
            .put("rows", JSONArray()
                .put(JSONArray().put("allocation_tolerance_pct").put(1.5))
                .put(JSONArray().put("reserve_target_toman").put(22_000_000.0))
                .put(JSONArray().put("group_tolerance_pct:crypto").put(2.5))))
        val policy = JSONObject()
            .put("groups", JSONArray().put(JSONObject().put("id", "crypto")
                .put("name", "Digital Assets").put("targetPercent", 40.0)
                .put("sortOrder", 2).put("pricingCurrency", "USDT")))
            .put("assets", JSONArray().put(JSONObject().put("id", "btc")
                .put("name", "Bitcoin").put("groupId", "crypto")
                .put("currency", "USDT").put("targetWithinGroupPercent", 25.0)
                .put("effectiveTargetPercent", 10.0)))
            .put("allocationTolerancePercent", 1.5)
            .put("groupToleranceOverrides", JSONArray().put(JSONObject()
                .put("id", "crypto").put("percent", 2.5)))
            .put("assetToleranceOverrides", JSONArray())
            .put("reserveTargetToman", 22_000_000.0)
        payload.getJSONObject("preview").put("policy", policy)
        return wrap(payload, version = 2)
    }

    @Test fun version2PreservesTwoLevelTargetsAndTolerance() {
        val result = CoreSnapshotPreview.inspect(syntheticV2().toString())
        val policy = result.policy!!
        assertEquals(40.0, policy.groups[0].target, 0.00001)
        assertEquals(25.0, policy.assets[0].within, 0.00001)
        assertEquals(10.0, policy.assets[0].effective, 0.00001)
        assertEquals(22_000_000.0, policy.reserve, 0.00001)
        assertEquals(1, policy.groupOverrides)
        assertEquals(true, result.display(true).contains("سیاست سرمایه‌گذاری"))
    }

    @Test fun correctChecksumWithWrongEffectiveTargetIsRejected() {
        val v2 = syntheticV2()
        val payload = JSONObject(String(Base64.getDecoder().decode(v2.getString("payloadBase64"))))
        payload.getJSONObject("preview").getJSONObject("policy")
            .getJSONArray("assets").getJSONObject(0).put("effectiveTargetPercent", 30.0)
        assertThrows(IllegalArgumentException::class.java) {
            CoreSnapshotPreview.inspect(wrap(payload, 2).toString())
        }
    }

    @Test fun correctChecksumWithWrongGroupTargetIsRejected() {
        val v2 = syntheticV2()
        val payload = JSONObject(String(Base64.getDecoder().decode(v2.getString("payloadBase64"))))
        payload.getJSONObject("preview").getJSONObject("policy")
            .getJSONArray("groups").getJSONObject(0).put("targetPercent", 90.0)
        assertThrows(IllegalArgumentException::class.java) {
            CoreSnapshotPreview.inspect(wrap(payload, 2).toString())
        }
    }

    @Test fun version2WrongToleranceReferenceIsRejected() {
        val v2 = syntheticV2()
        val payload = JSONObject(String(Base64.getDecoder().decode(v2.getString("payloadBase64"))))
        payload.getJSONObject("preview").getJSONObject("policy")
            .getJSONArray("groupToleranceOverrides").getJSONObject(0).put("id", "ghost")
        assertThrows(IllegalArgumentException::class.java) {
            CoreSnapshotPreview.inspect(wrap(payload, 2).toString())
        }
    }

    @Test fun version2MissingPolicyIsRejected() {
        val v2 = syntheticV2()
        val payload = JSONObject(String(Base64.getDecoder().decode(v2.getString("payloadBase64"))))
        payload.getJSONObject("preview").remove("policy")
        assertThrows(Exception::class.java) {
            CoreSnapshotPreview.inspect(wrap(payload, 2).toString())
        }
    }

    @Test fun unsupportedVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreSnapshotPreview.inspect(syntheticV2().put("contractVersion", 3).toString())
        }
    }

    @Test fun AndroidJsonIsNotAcceptedAsWindowsCore() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreSnapshotPreview.inspect("""{"format":"investment.shared.portfolio","schemaVersion":1}""")
        }
    }
}
