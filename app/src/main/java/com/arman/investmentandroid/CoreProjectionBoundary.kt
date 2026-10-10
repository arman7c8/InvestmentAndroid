package com.arman.investmentandroid

import java.util.Locale

/** Fail-closed origin check for a shared holdings projection, not a financial import. */
object CoreProjectionBoundary {
    /**
     * Windows v0.12 Core emits both windows:* IDs and source.kind=asset with
     * source.group_id/source.asset_id. Check independent markers so changing
     * one presentation ID cannot turn a projection into editable Android data.
     */
    fun isWindowsCoreAsset(
        origins: List<String>,
        identities: List<String>,
        nestedOrigins: List<String>,
        sourceKind: String,
        sourceGroupId: String,
        sourceAssetId: String
    ): Boolean {
        fun normalized(value: String) = value.trim().lowercase(Locale.US)
        val markers = origins + nestedOrigins
        if (markers.any { normalized(it) == "windows-core" }) return true
        if (identities.any { normalized(it).startsWith("windows:") }) return true
        return normalized(sourceKind) == "asset" &&
            sourceGroupId.isNotBlank() && sourceAssetId.isNotBlank()
    }
}
