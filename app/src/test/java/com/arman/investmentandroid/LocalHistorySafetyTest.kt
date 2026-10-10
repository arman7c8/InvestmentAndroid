package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalHistorySafetyTest {
    @Test fun newAndLegacyHistoriesStayReadable() {
        assertEquals(0, LocalHistorySafety.requireReadableBeforeOverwrite("[]"))
        assertEquals(1, LocalHistorySafety.requireReadableBeforeOverwrite(
            """[{"type":"BUY","quantity":2}]"""
        ))
        assertEquals(1, LocalHistorySafety.requireReadableBeforeOverwrite(
            """[{"id":"unique-1","type":"BUY"}]"""
        ))
    }

    @Test fun malformedOrTruncatedHistoryIsNeverSilentlyOverwritten() {
        for (raw in listOf(null, "", " ", "[", "not-json", "{}")) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalHistorySafety.requireReadableBeforeOverwrite(raw)
            }
        }
    }

    @Test fun duplicateOrBlankTransactionIdentityFailsClosed() {
        for (raw in listOf(
            """[{"id":"x"},{"id":"x"}]""",
            """[{"id":" "}]""",
            """[{"id":"x"},0]"""
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalHistorySafety.requireReadableBeforeOverwrite(raw)
            }
        }
    }
}
