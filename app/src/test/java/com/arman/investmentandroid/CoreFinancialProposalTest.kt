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

    @Test fun samePortableBuyFixtureAsWindowsPythonValidator() {
        val resource = javaClass.getResource("/core_mobile_financial_buy_v1.json")
            ?: error("Missing shared JSON contract fixture.")
        val fixture = JSONObject(resource.readText())
        val actual = JSONObject(CoreFinancialProposal.create(
            snapshot(), command("buy", asset = "btc", qty = 0.1),
            fixture.getString("operationId")
        ))
        assertEquals(fixture.getString("format"), actual.getString("format"))
        assertEquals(fixture.getInt("contractVersion"),
            actual.getInt("contractVersion"))
        assertEquals(fixture.getString("baseSnapshotSha256"),
            actual.getString("baseSnapshotSha256"))
        assertEquals(fixture.getString("operationId"), actual.getString("operationId"))
        val expectedTrade = fixture.getJSONObject("transaction")
        val actualTrade = actual.getJSONObject("transaction")
        assertEquals(expectedTrade.getString("assetId"), actualTrade.getString("assetId"))
        assertEquals(expectedTrade.getString("accountId"), actualTrade.getString("accountId"))
        assertEquals(expectedTrade.getDouble("amountToman"),
            actualTrade.getDouble("amountToman"), 1e-7)
        assertEquals(expectedTrade.getDouble("expectedAccountBalanceToman"),
            actualTrade.getDouble("expectedAccountBalanceToman"), 1e-7)
        assertEquals(expectedTrade.getDouble("expectedAssetQuantity"),
            actualTrade.getDouble("expectedAssetQuantity"), 1e-12)
        assertEquals(expectedTrade.getDouble("quantity"),
            actualTrade.getDouble("quantity"), 1e-12)
        assertEquals(expectedTrade.length(), actualTrade.length())
        assertEquals(fixture.length(), actual.length())
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

    @Test fun preventsSubPrecisionDebitsAndCredits() {
        val zeroCash = snapshot().copy(cashBalances = listOf(
            CoreSnapshotPreview.CashPosition("wallet", 0.0)
        ))
        assertThrows(IllegalArgumentException::class.java) {
            CoreFinancialProposal.create(
                zeroCash, command("withdraw", amount = 1e-8)
            )
        }
        val hugeCash = snapshot().copy(cashBalances = listOf(
            CoreSnapshotPreview.CashPosition("wallet", 1e15)
        ))
        assertThrows(IllegalArgumentException::class.java) {
            CoreFinancialProposal.create(
                hugeCash, command("deposit", amount = 1e-8)
            )
        }
        val largeQuantity = snapshot().copy(positions = listOf(
            CoreSnapshotPreview.AssetPosition("btc", "Bitcoin", 1e12)
        ))
        assertThrows(IllegalArgumentException::class.java) {
            CoreFinancialProposal.create(
                largeQuantity, command("buy", amount = 100.0,
                    asset = "btc", qty = 1e-12)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreFinancialProposal.create(
                hugeCash.copy(positions = listOf(
                    CoreSnapshotPreview.AssetPosition("btc", "Bitcoin", 0.0)
                )),
                command("buy", amount = 1e15, asset = "btc", qty = 1e-300)
            )
        }
    }

    @Test fun retainsRepresentableFractionalBuy() {
        val purchase = command(
            "buy", amount = 0.01, asset = "btc", qty = 0.00001
        )
        val result = JSONObject(CoreFinancialProposal.create(snapshot(), purchase))
        assertEquals(0.01,
            result.getJSONObject("transaction").getDouble("amountToman"), 1e-10)
    }

    @Test fun proposalRejectsNonUuidIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreFinancialProposal.create(snapshot(), command("deposit"), "not-a-uuid")
        }
    }
}
