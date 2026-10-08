package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/**
 * Portable Core policy *proposal*, never a portfolio mutation or a Drive sync.
 * Expected values are taken from the checksum-verified Core v2 preview; changes
 * do not silently redistribute other assets or groups.
 */
object CorePolicyProposal {
    const val FORMAT = "investment.core.policy.proposal"
    const val VERSION = 1
    data class Edit(val scope: String, val id: String, val value: Double)
    private val scopes = setOf(
        "group_target", "asset_target", "allocation_tolerance", "reserve_target"
    )
    private fun validNumber(value: Double, upper: Double? = null): Boolean =
        value.isFinite() && value >= 0.0 && (upper == null || value <= upper)

    fun create(
        snapshot: CoreSnapshotPreview.Summary,
        edits: List<Edit>,
        operationId: String = UUID.randomUUID().toString()
    ): String {
        val policy = requireNotNull(snapshot.policy) {
            "Windows Core v2 policy is required. No edits were made."
        }
        require(Regex("[0-9a-f]{64}").matches(snapshot.sha)) {
            "Windows snapshot checksum is missing."
        }
        require(operationId.lowercase() == UUID.fromString(operationId).toString()) {
            "Invalid policy operation ID."
        }
        require(edits.isNotEmpty() && edits.size <= 100) {
            "Enter one to one hundred policy changes."
        }
        val groups = policy.groups.associate { it.id to it.target }.toMutableMap()
        val assets = policy.assets.associate { it.id to it.within }.toMutableMap()
        var tolerance = policy.tolerance
        var reserve = policy.reserve
        var hasTarget = false
        val seen = mutableSetOf<Pair<String, String>>()
        val changes = JSONArray()
        for (edit in edits) {
            require(edit.scope in scopes && (edit.scope to edit.id).let { seen.add(it) }) {
                "Unknown or repeated target change."
            }
            val limit = if (edit.scope == "reserve_target") null else 100.0
            require(validNumber(edit.value, limit)) { "Invalid proposed policy value." }
            val expected = when (edit.scope) {
                "group_target" -> groups[edit.id] ?: error("Unknown Windows group.")
                "asset_target" -> assets[edit.id] ?: error("Unknown Windows asset.")
                "allocation_tolerance" -> {
                    require(edit.id.isEmpty()) { "Global tolerance cannot have an asset ID." }
                    tolerance
                }
                "reserve_target" -> {
                    require(edit.id.isEmpty()) { "Cash reserve cannot have an asset ID." }
                    reserve
                }
                else -> error("Unsupported policy change.")
            }
            require(abs(expected - edit.value) > 1e-9) { "No-op changes are not accepted." }
            when (edit.scope) {
                "group_target" -> { groups[edit.id] = edit.value; hasTarget = true }
                "asset_target" -> { assets[edit.id] = edit.value; hasTarget = true }
                "allocation_tolerance" -> tolerance = edit.value
                "reserve_target" -> reserve = edit.value
            }
            changes.put(JSONObject().put("scope", edit.scope).put("id", edit.id)
                .put("expected", expected).put("value", edit.value))
        }
        if (hasTarget) {
            require(abs(groups.values.sum() - 100.0) < 1e-9) {
                "The group targets must sum to 100%."
            }
            for (group in policy.groups) {
                val members = policy.assets.filter { it.groupId == group.id }
                val memberTotal = members.sumOf { assets.getValue(it.id) }
                require(if (members.isEmpty()) abs(groups.getValue(group.id)) < 1e-9
                    else abs(memberTotal - 100.0) < 1e-9) {
                    "Targets within every group must total 100%."
                }
            }
        }
        return JSONObject()
            .put("format", FORMAT).put("contractVersion", VERSION)
            .put("operationId", operationId)
            .put("baseSnapshotSha256", snapshot.sha)
            .put("changes", changes)
            .toString(2)
    }
}
