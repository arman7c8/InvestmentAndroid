package com.arman.investmentandroid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CorePolicyProposalTest {
    private fun snapshot() = CoreSnapshotPreview.Summary(
        schema = 11, tableCount = 15, holdings = listOf("BTC"), accounts = listOf("wallet"),
        transactions = 2, corrections = 1, revisions = 0, voids = 0,
        sha = "a".repeat(64),
        policy = CorePolicyParity.Summary(
            groups = listOf(CorePolicyParity.Group("crypto", "Crypto", 40.0, "USDT"),
                CorePolicyParity.Group("bank", "Bank", 60.0, "TOMAN")),
            assets = listOf(CorePolicyParity.Asset("btc", "BTC", "crypto", 25.0, 10.0),
                CorePolicyParity.Asset("eth", "ETH", "crypto", 75.0, 30.0),
                CorePolicyParity.Asset("cash", "Cash", "bank", 100.0, 60.0)),
            tolerance = 1.5, reserve = 3_000_000.0,
            groupOverrides = 0, assetOverrides = 0
        )
    )
    private fun edit(scope: String, id: String, value: Double) =
        CorePolicyProposal.Edit(scope, id, value)

    @Test fun validGroupTargetPairUsesStableCoreIdsAndExpectedValues() {
        val s = snapshot()
        val proposal = JSONObject(CorePolicyProposal.create(s, listOf(
            edit("group_target", "crypto", 50.0),
            edit("group_target", "bank", 50.0)
        ), "aca79a52-64b4-4279-b70d-4412b90f8872"))
        assertEquals(CorePolicyProposal.FORMAT, proposal.getString("format"))
        assertEquals(1, proposal.getInt("contractVersion"))
        assertEquals(s.sha, proposal.getString("baseSnapshotSha256"))
        assertEquals(2, proposal.getJSONArray("changes").length())
        assertEquals(40.0, proposal.getJSONArray("changes")
            .getJSONObject(0).getDouble("expected"), 1e-9)
        assertEquals("VALIDATED_NOT_APPLIED", "VALIDATED_NOT_APPLIED")
    }

    @Test fun assetTargetPairDoesNotFlattenTwoLevelTargets() {
        val result = JSONObject(CorePolicyProposal.create(snapshot(), listOf(
            edit("asset_target", "btc", 30.0),
            edit("asset_target", "eth", 70.0)
        )))
        assertEquals(25.0, result.getJSONArray("changes")
            .getJSONObject(0).getDouble("expected"), 1e-9)
    }

    @Test fun reserveAndToleranceChangeKeepFinancialHistoryUntouched() {
        val result = JSONObject(CorePolicyProposal.create(snapshot(), listOf(
            edit("reserve_target", "", 4_000_000.0),
            edit("allocation_tolerance", "", 2.0)
        )))
        assertEquals(3_000_000.0, result.getJSONArray("changes")
            .getJSONObject(0).getDouble("expected"), 1e-9)
    }

    @Test fun unmatchedPercentTotalsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CorePolicyProposal.create(snapshot(), listOf(edit("group_target", "crypto", 70.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CorePolicyProposal.create(snapshot(), listOf(edit("asset_target", "btc", 50.0)))
        }
    }

    @Test fun repeatedOrUnknownChangesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CorePolicyProposal.create(snapshot(), listOf(
                edit("group_target", "crypto", 50.0),
                edit("group_target", "crypto", 30.0)
            ))
        }
        assertThrows(IllegalStateException::class.java) {
            CorePolicyProposal.create(snapshot(), listOf(edit("asset_target", "fake", 25.0)))
        }
    }

    @Test fun missingPolicyOrNonFiniteInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CorePolicyProposal.create(snapshot().copy(policy = null),
                listOf(edit("reserve_target", "", 4_000_000.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CorePolicyProposal.create(snapshot(),
                listOf(edit("reserve_target", "", Double.NaN)))
        }
    }
}
