package com.arman.investmentandroid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreFinancialProposalTest {
    private fun snapshot() = CoreSnapshotPreview.Summary(
        schema = 11, tableCount = 12, holdings = emptyList(),
        accounts = emptyList(), transactions = 4, corrections = 1,
        revisions = 1, voids = 1, sha = "a".repeat(64),
        policy = CorePolicyParity.Summary(emptyList(), emptyList(), 1.5,
            0.0, 0, 0),
        positions = listOf(
            CoreSnapshotPreview.AssetPosition("btc", "Bitcoin", 1.05),
            CoreSnapshotPreview.AssetPosition("eth", "Ethereum", 0.2)
        ),
        cashBalances = listOf(
            CoreSnapshotPreview.CashPosition("wallet", 1_300_000.0),
            CoreSnapshotPreview.CashPosition("bank", 400_000.0)
        )
    )
    private fun command(
        type: String, amount: Double = 100_000.0, asset: String? = null,
        account: String = "wallet", qty: Double? = null, destination: String? = null
    ) = CoreFinancialProposal.Command(type, amount,
        assetId = asset, accountId = account, quantity = qty,
        destinationAccountId = destination)

    @Test fun buyUsesStableAssetAndAccountIdsAndPreviousValues() {
        val s = snapshot()
        val before = s.copy()
        val raw = CoreFinancialProposal.create(s, command("buy", asset = "btc", qty = 0.1),
            "aca79a52-64b4-4279-b70d-4412b90f8872")
        val proposal = JSONObject(raw)
        val tx = proposal.getJSONObject("transaction")
        assertEquals(CoreFinancialProposal.FORMAT, proposal.getString("format"))
        assertEquals(1, proposal.getInt("contractVersion"))
        assertEquals(s.sha, proposal.getString("baseSnapshotSha256"))
        assertEquals("btc", tx.getString("assetId"))
        assertEquals("wallet", tx.getString("accountId"))
        assertEquals(1.05, tx.getDouble("expectedAssetQuantity"), 1e-10)
        assertEquals(1_300_000.0, tx.getDouble("expectedAccountBalanceToman"), 1e-7)
        assertEquals(100_000.0, tx.getDouble("amountToman"), 1e-7)
        assertEquals(before, s)
    }

    @Test fun sellAndTransferCarryCorrectCashSemantics() {
        val sell = JSONObject(CoreFinancialProposal.create(snapshot(),
            command("sell", asset = "btc", account = "bank", qty = 0.2)))
            .getJSONObject("transaction")
        assertEquals(400_000.0, sell.getDouble("expectedAccountBalanceToman"), 1e-7)
        val transfer = JSONObject(CoreFinancialProposal.create(snapshot(),
            command("transfer", destination = "bank"))).getJSONObject("transaction")
        assertEquals("wallet", transfer.getString("sourceAccountId"))
        assertEquals("bank", transfer.getString("destinationAccountId"))
        assertEquals(1_300_000.0, transfer.getDouble("expectedSourceBalanceToman"), 1e-7)
        assertEquals(400_000.0, transfer.getDouble("expectedDestinationBalanceToman"), 1e-7)
    }

    @Test fun depositAndWithdrawalUseExistingAccounts() {
        val deposit = JSONObject(CoreFinancialProposal.create(snapshot(),
            command("deposit", account = "bank"))).getJSONObject("transaction")
        assertEquals("bank", deposit.getString("accountId"))
        val withdraw = JSONObject(CoreFinancialProposal.create(snapshot(),
            command("withdraw"))).getJSONObject("transaction")
        assertEquals("withdraw", withdraw.getString("type"))
    }

    @Test fun cannotSpendMoreCashOrSellMoreQuantityThanCoreReports() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreFinancialProposal.create(snapshot(),
                command("buy", amount = 1_300_001.0, asset = "btc", qty = 0.001))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreFinancialProposal.create(snapshot(),
                command("sell", asset = "btc", qty = 1.051))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreFinancialProposal.create(snapshot(),
                command("transfer", amount = 1_300_001.0, destination = "bank"))
        }
    }

    @Test fun incorrectAccountOrUnknownAssetIsRejected() {
        for (bad in listOf(
            command("buy", asset = "fake", qty = 0.1),
            command("buy", asset = "btc", account = "external", qty = 0.1),
            command("transfer", destination = "wallet"),
            command("transfer", destination = "unknown"),
            command("swap", asset = "btc")
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                CoreFinancialProposal.create(snapshot(), bad)
            }
        }
    }

    @Test fun nonFiniteZeroOrMalformedValueFailsClosed() {
        for (bad in listOf(
            command("buy", amount = 0.0, asset = "btc", qty = 0.1),
            command("buy", asset = "btc", qty = Double.NaN),
            command("buy", asset = "btc", qty = 0.0),
            command("withdraw", amount = Double.POSITIVE_INFINITY)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                CoreFinancialProposal.create(snapshot(), bad)
            }
        }
    }

    @Test fun cannotPrepareWithoutCoreV2AndVerifiedSnapshotIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreFinancialProposal.create(snapshot().copy(policy = null),
                command("deposit"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreFinancialProposal.create(snapshot().copy(sha = "bogus"),
                command("deposit"))
        }
    }

    @Test fun proposalRejectsNonUuidIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreFinancialProposal.create(snapshot(), command("deposit"), "not-a-uuid")
        }
    }
}
