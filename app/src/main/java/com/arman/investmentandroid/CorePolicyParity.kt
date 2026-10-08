package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Read-only v2 allocation-policy parity check; never writes Android holdings. */
object CorePolicyParity {
    data class Group(val id: String, val name: String, val target: Double, val currency: String)
    data class Asset(val id: String, val name: String, val groupId: String,
        val within: Double, val effective: Double)
    data class Summary(val groups: List<Group>, val assets: List<Asset>,
        val tolerance: Double, val reserve: Double, val groupOverrides: Int,
        val assetOverrides: Int) {
        fun display(fa: Boolean): List<String> {
            val lines = mutableListOf(
                if (fa) "سیاست سرمایه‌گذاری ویندوز (فقط‌خواندنی):"
                else "Windows investment policy (read-only):"
            )
            groups.take(30).forEach { group ->
                lines.add(group.name + ": " + group.target + "% — " + group.currency)
                assets.filter { it.groupId == group.id }.take(60).forEach { asset ->
                    lines.add("  " + asset.name + ": " + asset.within + "% → " + asset.effective + "%")
                }
            }
            lines.add((if (fa) "حد تحمل کلی: " else "Global tolerance: ") + tolerance + "%")
            lines.add((if (fa) "هدف ذخیره نقدی: " else "Cash reserve target: ") + reserve + " Toman")
            lines.add((if (fa) "حدود تحمل اختصاصی: " else "Tolerance overrides: ") +
                (groupOverrides + assetOverrides))
            return lines
        }
    }

    private fun rows(tables: JSONObject, name: String): List<JSONObject> {
        val table = tables.getJSONObject(name)
        val columns = table.getJSONArray("columns")
        val records = table.getJSONArray("rows")
        val names = (0 until columns.length()).map { columns.getString(it) }
        require(names.isNotEmpty() && names.distinct().size == names.size) {
            "Invalid policy columns: " + name
        }
        return (0 until records.length()).map { index ->
            val row = records.getJSONArray(index)
            require(row.length() == names.size) { "Invalid policy row in " + name }
            JSONObject().apply {
                names.forEachIndexed { i, key -> put(key, row.get(i)) }
            }
        }
    }

    private fun finite(value: Any?, name: String): Double {
        require(value is Number && value.toDouble().isFinite()) {
            "Nonfinite or missing Core policy field: " + name
        }
        return (value as Number).toDouble()
    }
    private fun same(value: Any?, expected: Double, field: String) {
        val actual = finite(value, field)
        require(abs(actual - expected) <=
            1e-12 * max(1.0, max(abs(actual), abs(expected)))) {
            "Core policy mismatch: " + field
        }
    }

    fun inspect(tables: JSONObject, policy: JSONObject): Summary {
        val rawGroups = rows(tables, "portfolio_groups")
            .sortedWith(compareBy<JSONObject> { it.getInt("sort_order") }
                .thenBy { it.getString("id") })
        val shownGroups = policy.getJSONArray("groups")
        require(shownGroups.length() == rawGroups.size) { "Group policy count mismatch." }
        val groupIds = mutableSetOf<String>()
        val groups = rawGroups.mapIndexed { index, original ->
            val id = original.getString("id")
            require(id.isNotBlank() && groupIds.add(id)) { "Duplicate Core group." }
            val shown = shownGroups.getJSONObject(index)
            require(shown.getString("id") == id &&
                shown.getString("name") == original.getString("name") &&
                shown.getString("pricingCurrency") ==
                    original.getString("pricing_currency").uppercase() &&
                shown.getInt("sortOrder") == original.getInt("sort_order")) {
                "Core group identity/currency differs."
            }
            val target = finite(original.get("target_pct"), "group target")
            require(target >= 0) { "Negative group target." }
            same(shown.get("targetPercent"), target, "group target")
            Group(id, original.getString("name"), target,
                original.getString("pricing_currency"))
        }
        val groupTargets = groups.associate { it.id to it.target }
        val rawAssets = rows(tables, "assets")
            .sortedWith(compareBy<JSONObject> { it.getString("category") }
                .thenBy { it.getString("id") })
        val shownAssets = policy.getJSONArray("assets")
        require(shownAssets.length() == rawAssets.size) { "Asset policy count mismatch." }
        val assetIds = mutableSetOf<String>()
        val assets = rawAssets.mapIndexed { index, original ->
            val id = original.getString("id")
            val gid = original.getString("category")
            require(id.isNotBlank() && assetIds.add(id) && gid in groupTargets) {
                "Unknown or duplicate Core asset identity."
            }
            val shown = shownAssets.getJSONObject(index)
            require(shown.getString("id") == id &&
                shown.getString("groupId") == gid &&
                shown.getString("name") == original.getString("name") &&
                shown.getString("currency") == original.getString("currency").uppercase()) {
                "Core asset identity/currency differs."
            }
            val within = finite(original.get("target_pct"), "asset target")
            require(within >= 0) { "Negative asset target." }
            val effective = groupTargets.getValue(gid) * within / 100.0
            same(shown.get("targetWithinGroupPercent"), within, "within-group target")
            same(shown.get("effectiveTargetPercent"), effective, "effective target")
            Asset(id, original.getString("name"), gid, within, effective)
        }
        val settings = linkedMapOf<String, Double>()
        for (row in rows(tables, "portfolio_settings")) {
            val key = row.getString("key")
            require(key.isNotBlank() && key !in settings) { "Duplicate policy setting." }
            settings[key] = finite(row.get("value"), key)
        }
        val tolerance = settings["allocation_tolerance_pct"] ?: 1.0
        val reserve = settings["reserve_target_toman"] ?: 0.0
        require(tolerance in 0.0..100.0 && reserve >= 0.0) {
            "Invalid Core policy tolerance/reserve."
        }
        same(policy.get("allocationTolerancePercent"), tolerance, "tolerance")
        same(policy.get("reserveTargetToman"), reserve, "reserve")
        fun overrides(prefix: String, ids: Set<String>, shown: JSONArray): Int {
            val source = settings.filterKeys { it.startsWith(prefix) }.toSortedMap()
            require(source.size == shown.length()) { "Tolerance count mismatch." }
            source.entries.forEachIndexed { index, entry ->
                val id = entry.key.removePrefix(prefix)
                require(id in ids && entry.value in 0.0..100.0) {
                    "Invalid tolerance override."
                }
                val target = shown.getJSONObject(index)
                require(target.getString("id") == id) { "Wrong tolerance ID." }
                same(target.get("percent"), entry.value, "individual tolerance")
            }
            return source.size
        }
        val groupCount = overrides("group_tolerance_pct:", groupIds,
            policy.getJSONArray("groupToleranceOverrides"))
        val assetCount = overrides("asset_tolerance_pct:", assetIds,
            policy.getJSONArray("assetToleranceOverrides"))
        return Summary(groups, assets, tolerance, reserve, groupCount, assetCount)
    }
}
