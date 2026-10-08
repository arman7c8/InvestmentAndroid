package com.arman.investmentandroid

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Read-only verifier; cannot mutate Android data or its cloud destination. */
object CoreSnapshotPreview {
    data class AssetPosition(val id: String, val name: String, val quantity: Double)
    data class CashPosition(val id: String, val balanceToman: Double)

    data class Summary(val schema: Int, val tableCount: Int, val holdings: List<String>,
        val accounts: List<String>, val transactions: Int, val corrections: Int,
        val revisions: Int, val voids: Int, val sha: String,
        val policy: CorePolicyParity.Summary? = null,
        val positions: List<AssetPosition> = emptyList(),
        val cashBalances: List<CashPosition> = emptyList()) {
        fun display(fa: Boolean): String {
            val lines = mutableListOf<String>()
            lines.add(if (fa) "فقط پیش‌نمایش؛ هیچ داده‌ای وارد یا همگام نمی‌شود." else
                "Read-only preview. No data is imported or synchronized.")
            lines.add((if (fa) "نسخه Core: " else "Core schema: ") + schema)
            lines.add((if (fa) "تعداد جدول‌ها: " else "Tables: ") + tableCount)
            lines.add((if (fa) "تراکنش‌ها: " else "Transactions: ") + transactions)
            lines.add((if (fa) "اصلاح مقدار / ویرایش / ابطال: " else "Corrections / revisions / voids: ") +
                corrections + " / " + revisions + " / " + voids)
            lines.add(if (fa) "دارایی‌ها:" else "Holdings:")
            lines.addAll(holdings.take(80))
            if (holdings.size > 80) lines.add("… " + (holdings.size - 80) + " more")
            lines.add(if (fa) "حساب‌های نقدی:" else "Cash accounts:")
            lines.addAll(accounts.take(40))
            policy?.let { lines.addAll(it.display(fa)) }
            lines.add("SHA-256: " + sha)
            return lines.joinToString("\n")
        }
    }

    fun inspect(raw: String): Summary {
        require(raw.length <= 20_000_000) { "Core preview is too large for this Android reader." }
        val envelope = JSONObject(raw)
        require(envelope.optString("format") == "investment.core.readonly") {
            "Not a Windows Core read-only export."
        }
        val contractVersion = envelope.optInt("contractVersion", -1)
        require(contractVersion == 1 || contractVersion == 2) {
            "Unsupported Core interchange version."
        }
        require(envelope.optString("encoding") == "base64-json-utf8") { "Unsupported export encoding." }
        val sha = envelope.getString("sha256")
        require(Regex("[0-9a-f]{64}").matches(sha)) { "Missing or invalid SHA-256." }
        val encoded = envelope.getString("payloadBase64")
        require(encoded.length <= 20_000_000) { "Encoded Core preview exceeds limit." }
        val decoded = Base64.getDecoder().decode(encoded)
        require(decoded.size <= 15_000_000) { "Decoded Core preview exceeds limit." }
        val digest = MessageDigest.getInstance("SHA-256").digest(decoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(digest == sha) { "Core preview checksum mismatch; no data was changed." }

        val payload = JSONObject(String(decoded, Charsets.UTF_8))
        val source = payload.getJSONObject("source")
        require(source.optString("platform") == "windows-core") { "Unexpected source platform." }
        val schema = source.getInt("schemaVersion")
        require(schema == 11) { "Unsupported Windows Core database schema." }
        val tables = payload.getJSONObject("tables")
        val required = listOf("schema_meta", "imported_snapshots", "opening_accounts",
            "opening_positions", "assets", "portfolio_groups", "transactions", "prices",
            "quantity_corrections", "transaction_revisions", "transaction_voids")
        require(required.all { tables.has(it) }) { "Incomplete Core ledger. No data was changed." }
        val names = tables.keys()
        var count = 0
        var totalRows = 0
        while (names.hasNext()) {
            val name = names.next()
            val table = tables.getJSONObject(name)
            val columns = table.getJSONArray("columns")
            val rows = table.getJSONArray("rows")
            val rowids = table.getJSONArray("rowids")
            require(columns.length() > 0 && columns.length() ==
                (0 until columns.length()).map { columns.getString(it) }.distinct().size) {
                "Invalid columns in " + name
            }
            require(rows.length() == rowids.length()) { "Row identity mismatch in " + name }
            totalRows += rows.length()
            require(totalRows <= 200_000) { "Too many Core records for preview." }
            for (i in 0 until rows.length()) {
                require(rows.getJSONArray(i).length() == columns.length()) { "Invalid row width in " + name }
                require(rowids.get(i) is Number) { "Invalid row identity in " + name }
            }
            count++
        }

        val preview = payload.getJSONObject("preview")
        val assets = preview.getJSONArray("holdings")
        val accountRows = preview.getJSONArray("cashAccounts")
        require(assets.length() == tables.getJSONObject("assets").getJSONArray("rows").length()) {
            "Holdings projection and source asset count differ."
        }
        val holdings = (0 until assets.length()).map { index ->
            val item = assets.getJSONObject(index)
            val qty = item.getDouble("quantity")
            require(qty.isFinite()) { "Non-finite quantity." }
            val value = if (item.isNull("value_toman")) "unpriced" else {
                val amount = item.getDouble("value_toman")
                require(amount.isFinite()) { "Non-finite value." }
                amount.toString() + " Toman"
            }
            item.getString("name") + " (" + item.getString("id") + "): " + qty + " — " + value
        }
        val accounts = (0 until accountRows.length()).map { index ->
            val item = accountRows.getJSONObject(index)
            val value = item.getDouble("balance_toman")
            require(value.isFinite()) { "Non-finite account balance." }
            item.getString("id") + ": " + value + " Toman"
        }
        CoreLedgerParity.verify(tables, preview)
        val tx = preview.getInt("transactionCount")
        val corrections = preview.getInt("correctionCount")
        val revisions = preview.getInt("revisionCount")
        val voids = preview.getInt("voidCount")
        for ((table, value) in listOf("transactions" to tx, "quantity_corrections" to corrections,
                "transaction_revisions" to revisions, "transaction_voids" to voids)) {
            require(value == tables.getJSONObject(table).getJSONArray("rows").length()) {
                "Preview count mismatch in " + table
            }
        }
        val policy = if (contractVersion == 2) {
            CorePolicyParity.inspect(tables, preview.getJSONObject("policy"))
        } else null
        // The immutable, independently verified source preview supplies stable
        // Core IDs and quantities to the offline financial request editor.
        val positions = (0 until assets.length()).map { index ->
            val item = assets.getJSONObject(index)
            AssetPosition(item.getString("id"), item.getString("name"),
                item.getDouble("quantity"))
        }
        val balances = (0 until accountRows.length()).map { index ->
            val item = accountRows.getJSONObject(index)
            CashPosition(item.getString("id"), item.getDouble("balance_toman"))
        }
        return Summary(schema, count, holdings, accounts, tx, corrections, revisions,
            voids, sha, policy, positions, balances)
    }
}
