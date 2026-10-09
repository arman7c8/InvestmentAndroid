package com.arman.investmentandroid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCoreFinancialJournalTest {
    private class MemoryStorage : PendingCoreFinancialJournal.Storage {
        var json: String? = null
        var failWrite = false
        var ignoreClear = false
        override fun read(): String? = json
        override fun write(value: String) {
            if (failWrite) throw IllegalStateException("Synthetic storage failure")
            json = value
        }
        override fun clear() {
            if (!ignoreClear) json = null
        }
    }
    private fun request(uuid: String = "3a79b77c-a9d9-4928-aeae-6671d68dc2c2") =
        JSONObject().put("format", CoreFinancialProposal.FORMAT)
            .put("contractVersion", CoreFinancialProposal.VERSION)
            .put("operationId", uuid)
            .put("baseSnapshotSha256", "a".repeat(64))
            .put("transaction", JSONObject()
                .put("type", "buy").put("assetId", "btc")
                .put("accountId", "wallet").put("amountToman", 100.0)
                .put("quantity", 0.001))
            .toString(2)

    @Test fun durableReloadKeepsExactOriginalOperationIdAfterFreshInstance() {
        val storage = MemoryStorage()
        val first = PendingCoreFinancialJournal(storage)
        val raw = request()
        assertEquals(raw, first.save(raw))
        val afterRestart = PendingCoreFinancialJournal(storage)
        assertEquals(raw, afterRestart.load())
        assertEquals(raw, afterRestart.save(raw))
        assertEquals(PendingCoreFinancialRequest.operationId(raw),
            PendingCoreFinancialRequest.operationId(afterRestart.load()))
    }

    @Test fun newUuidCannotOverwriteOutstandingFinancialCommand() {
        val storage = MemoryStorage()
        val journal = PendingCoreFinancialJournal(storage)
        val old = request()
        journal.save(old)
        assertThrows(IllegalArgumentException::class.java) {
            journal.save(request("b4fe659e-5d9d-48e6-8c36-9a591f985a1a"))
        }
        assertEquals(old, journal.load())
    }

    @Test fun failedWritePreservesOlderPendingOperation() {
        val storage = MemoryStorage()
        val old = request()
        storage.json = old
        storage.failWrite = true
        val journal = PendingCoreFinancialJournal(storage)
        assertThrows(IllegalStateException::class.java) { journal.save(old) }
        assertEquals(old, journal.load())
    }

    @Test fun corruptExistingJournalFailsClosedRatherThanCreatingNewUuid() {
        val storage = MemoryStorage()
        storage.json = "{not-json"
        val journal = PendingCoreFinancialJournal(storage)
        assertThrows(IllegalStateException::class.java) { journal.load() }
        assertThrows(IllegalStateException::class.java) { journal.save(request()) }
        assertEquals("{not-json", storage.json)
        journal.discardByUser()
        assertNull(journal.load())
    }

    @Test fun exportingFileDoesNotClearTheRequestOrRegenerateUuid() {
        val storage = MemoryStorage()
        val journal = PendingCoreFinancialJournal(storage)
        val previous = journal.save(request())
        // The document picker can export multiple identical copies. A copy is
        // NOT an authenticated Windows receipt, so the UUID stays pending.
        val exportCopy = journal.load()
        assertEquals(previous, exportCopy)
        assertEquals(previous, journal.save(exportCopy!!))
        assertEquals(previous, PendingCoreFinancialJournal(storage).load())
    }

    @Test fun failureToDiscardIsDetectedWithoutMisleadingSuccess() {
        val storage = MemoryStorage()
        val journal = PendingCoreFinancialJournal(storage)
        val previous = journal.save(request())
        storage.ignoreClear = true
        val ex = assertThrows(IllegalStateException::class.java) {
            journal.discardByUser()
        }
        assertTrue(ex.message!!.contains("Could not discard"))
        assertEquals(previous, journal.load())
    }

    @Test fun differentOperationAllowedOnlyAfterExplicitDiscard() {
        val storage = MemoryStorage()
        val journal = PendingCoreFinancialJournal(storage)
        journal.save(request())
        journal.discardByUser()
        val different = request("b4fe659e-5d9d-48e6-8c36-9a591f985a1a")
        assertEquals(different, journal.save(different))
    }
}
