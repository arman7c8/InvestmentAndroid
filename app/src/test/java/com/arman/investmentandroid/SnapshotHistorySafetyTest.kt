package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SnapshotHistorySafetyTest {
    @Test fun emptyAndValidHistoryAreAccepted() {
        assertEquals(0, SnapshotHistorySafety.requireReadableBeforeOverwrite("[]"))
        assertEquals(1, SnapshotHistorySafety.requireReadableBeforeOverwrite(
            """[{"timestamp":123,"totalValue":60000}]"""
        ))
    }

    @Test fun damagedHistoryMustNotBeResetSilently() {
        for (raw in listOf(null, "", " ", "[", "{}", """[{"timestamp":"bad","totalValue":5}]""",
            """[{"timestamp":1,"totalValue":-100}]""", """[null]""")) {
            assertThrows(IllegalArgumentException::class.java) {
                SnapshotHistorySafety.requireReadableBeforeOverwrite(raw)
            }
        }
    }

    @Test fun historyDoesNotCreateOrDeduplicateEvents() {
        val raw = """[{"timestamp":100,"totalValue":20},{"timestamp":100,"totalValue":20}]"""
        assertEquals(2, SnapshotHistorySafety.requireReadableBeforeOverwrite(raw))
    }
}
