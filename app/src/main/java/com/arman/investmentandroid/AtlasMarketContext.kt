package com.arman.investmentandroid

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.math.abs

object AtlasMarketContext {
    private const val URL_VALUE =
        "https://raw.githubusercontent.com/arman7c8/CryptoAIAnalyst/main/public/investment-market-context.json"
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_AGE_SECONDS = 36L * 60L * 60L

    fun loadOrNull(now: Instant = Instant.now()): AiAdvisorContract.AtlasContext? {
        return try {
            val connection = URL(URL_VALUE).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode != 200) {
                connection.disconnect()
                return null
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            connection.disconnect()

            val value = JSONObject(String(bytes, StandardCharsets.UTF_8))
            if (
                value.optString("format") != "atlas.market.context" ||
                value.optInt("schema_version", -1) != 1 ||
                !value.optBoolean("public_market_data_only", false)
            ) return null

            val generated = Instant.parse(value.optString("generated_at"))
            if (abs(now.epochSecond - generated.epochSecond) > MAX_AGE_SECONDS) return null

            val signalsJson = value.optJSONArray("signals")
            val signals = mutableListOf<AiAdvisorContract.AtlasSignal>()
            if (signalsJson != null) {
                for (index in 0 until minOf(signalsJson.length(), 24)) {
                    val item = signalsJson.optJSONObject(index) ?: continue
                    signals += AiAdvisorContract.AtlasSignal(
                        publicKey = item.optString("public_key").take(32),
                        direction = item.optString("direction", "neutral"),
                        confidence = item.optDouble("confidence", 50.0),
                        note = item.optString("note").take(280)
                    )
                }
            }
            AiAdvisorContract.AtlasContext(
                asOf = generated.toString(),
                riskRegime = value.optString("risk_regime", "unknown"),
                brief = value.optString("brief").take(1800),
                signals = signals
            )
        } catch (_: Exception) {
            null
        }
    }
}
