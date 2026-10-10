package com.arman.investmentandroid

import java.util.Locale

/**
 * Opaque, deterministic (within a sorted snapshot) AI identifiers.
 * Never export a private asset name, source ID or bank/cash account label.
 * Known public symbols may be retained if they do not collide.
 */
object AiPublicAssetKeys {
    private val symbols = setOf(
        "BTC", "ETH", "NEAR", "SOL", "TAO", "HYPE", "LINK",
        "USDT", "USD", "GOLD", "SILVER", "AYAR"
    )
    private val publicAliases = mapOf(
        "BITCOIN" to "BTC", "ETHEREUM" to "ETH", "SOLANA" to "SOL",
        "TETHER" to "USDT", "DOLLAR" to "USD",
        "GOLD" to "GOLD", "SILVER" to "SILVER"
    )

    fun generate(values: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return values.mapIndexed { index, raw ->
            val normalized = raw.trim().uppercase(Locale.ROOT)
            val candidate = publicAliases[normalized] ?: normalized
            val key = if (candidate in symbols && candidate !in used) {
                candidate
            } else {
                "ASSET-" + (index + 1)
            }
            check(used.add(key)) { "Unable to assign a unique public asset key." }
            key
        }
    }
}
