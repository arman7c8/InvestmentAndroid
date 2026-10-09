package com.arman.investmentandroid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingCoreFinancialRequestTest {
    private fun sample(): String = JSONObject()
        .put("format", CoreFinancialProposal.FORMAT)
        .put("contractVersion", CoreFinancialProposal.VERSION)
        .put("operationId", "3a79b77c-a9d9-4928-aeae-6671d68dc2c2")
        .put("baseSnapshotSha256", "a".repeat(64))
        .put("transaction", JSONObject().put("type", "buy")
            .put("assetId", "btc").put("accountId", "wallet")
            .put("amountToman", 100000.0).put("quantity", 0.1))
        .toString(2)

    @Test fun restoresExactRequestAndOperationId() {
        val json = sample()
        assertEquals(json, PendingCoreFinancialRequest.restore(json))
        assertEquals("3a79b77c-a9d9-4928-aeae-6671d68dc2c2",
            PendingCoreFinancialRequest.operationId(json))
    }
    @Test fun malformedAndWrongEnvelopeFailClosed() {
        assertNull(PendingCoreFinancialRequest.restore("{bad"))
        assertNull(PendingCoreFinancialRequest.restore(null))
        val json = JSONObject(sample()).put("format", "investment.shared.portfolio")
        assertNull(PendingCoreFinancialRequest.restore(json.toString()))
        json.put("format", CoreFinancialProposal.FORMAT)
            .put("baseSnapshotSha256", "bad")
        assertNull(PendingCoreFinancialRequest.restore(json.toString()))
    }
    @Test fun uppercaseOrInvalidUuidCannotBeRestored() {
        val json = JSONObject(sample())
        json.put("operationId", "3A79B77C-A9D9-4928-AEAE-6671D68DC2C2")
        assertNull(PendingCoreFinancialRequest.restore(json.toString()))
        json.put("operationId", "not-a-uuid")
        assertNull(PendingCoreFinancialRequest.restore(json.toString()))
    }
    @Test fun overlargeOrExtraFieldIsRejected() {
        assertNull(PendingCoreFinancialRequest.restore("a".repeat(
            PendingCoreFinancialRequest.MAX_BYTES + 1)))
        val json = JSONObject(sample()).put("forceApply", true)
        assertNull(PendingCoreFinancialRequest.restore(json.toString()))
    }
    @Test fun unsupportedTransactionTypeIsNotReused() {
        val json = JSONObject(sample())
        json.getJSONObject("transaction").put("type", "future_swap")
        assertNull(PendingCoreFinancialRequest.restore(json.toString()))
    }
}
