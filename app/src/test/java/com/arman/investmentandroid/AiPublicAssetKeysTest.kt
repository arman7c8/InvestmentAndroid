package com.arman.investmentandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AiPublicAssetKeysTest {
    @Test fun publicSymbolsRemainReadableWithoutPrivateNames() {
        assertEquals(
            listOf("BTC", "ETH", "ASSET-3", "ASSET-4"),
            AiPublicAssetKeys.generate(
                listOf("Bitcoin", "ETH", "My private deposit", "John's gold")
            )
        )
    }

    @Test fun duplicateSymbolsGetStableAnonymousKeys() {
        val identifiers = AiPublicAssetKeys.generate(
            listOf("BTC", "Bitcoin", "BTC", "ETH", "Ethereum")
        )
        assertEquals(listOf("BTC", "ASSET-2", "ASSET-3", "ETH", "ASSET-5"), identifiers)
        assertEquals(identifiers.size, identifiers.distinct().size)
        assertFalse(identifiers.joinToString().contains("Bitcoin"))
    }

    @Test fun privateIdentifierNeverLeaksIntoSnapshotKeys() {
        val identifiers = AiPublicAssetKeys.generate(
            listOf("Bank IBAN IR1234", "Apartment deposit", "Private Account")
        )
        assertEquals(listOf("ASSET-1", "ASSET-2", "ASSET-3"), identifiers)
    }
}
