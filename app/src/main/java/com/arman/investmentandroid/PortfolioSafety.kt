package com.arman.investmentandroid

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Strict, side-effect-free validation for every backup before MainActivity is
 * allowed to change local data. Keep this independent from Android APIs so the
 * data-loss rules can be covered by fast JVM unit tests.
 */
object PortfolioSafety {
    const val SHARED_FORMAT = "investment.shared.portfolio"
    const val SHARED_SCHEMA_VERSION = 1
    private const val MAX_ASSETS = 10_000
    private const val MAX_HISTORY_ROWS = 100_000

    enum class BackupKind {
        SHARED,
        LEGACY_ANDROID
    }

    enum class SyncDecision {
        MATCH,
        FIRST_SYNC_CONFLICT,
        LOAD_REMOTE,
        UPLOAD_LOCAL,
        CONFLICT
    }

    data class ValidatedBackup(
        val root: JSONObject,
        val kind: BackupKind,
        val sharedPortfolio: JSONObject?,
        val androidPayload: JSONObject?,
        val incomingAssetCount: Int
    )

    fun validateBackup(raw: String): ValidatedBackup {
        require(raw.isNotBlank()) { "Backup file is empty. Local data was not changed." }
        val root = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            throw IllegalArgumentException("Backup file is not valid JSON. Local data was not changed.")
        }

        return if (root.optString("format") == SHARED_FORMAT) {
            validateSharedDocument(root)
        } else {
            validateLegacyAndroidBackup(root)
        }
    }

    fun validateSharedDocument(root: JSONObject): ValidatedBackup {
        require(root.optString("format") == SHARED_FORMAT) {
            "Selected file is not an Investment shared portfolio."
        }
        require(requiredInt(root, "schemaVersion") == SHARED_SCHEMA_VERSION) {
            "Unsupported shared portfolio schema. Local data was not changed."
        }

        val portfolio = requiredObject(root, "sharedPortfolio")
        val count = validateSharedPortfolio(portfolio)
        val androidPayload = root.optJSONObject("androidBackup")
        if (root.has("androidBackup") && !root.isNull("androidBackup") && androidPayload == null) {
            throw IllegalArgumentException("Android backup section must be an object.")
        }
        androidPayload?.let(::validateAndroidSupplementalPayload)

        return ValidatedBackup(
            root = root,
            kind = BackupKind.SHARED,
            sharedPortfolio = portfolio,
            androidPayload = androidPayload,
            incomingAssetCount = count
        )
    }

    fun validateSharedPortfolio(portfolio: JSONObject): Int {
        if (portfolio.has("currency")) {
            require(portfolio.optString("currency") == "Toman") {
                "Shared portfolio currency must be Toman."
            }
        }

        val assets = requiredArray(portfolio, "assets")
        require(assets.length() <= MAX_ASSETS) { "Backup contains too many assets." }
        optionalFiniteNumber(portfolio, "rebalance_tolerance_percent", 0.0, 20.0)

        val identities = mutableSetOf<String>()
        for (index in 0 until assets.length()) {
            val item = assets.optJSONObject(index)
                ?: throw IllegalArgumentException("Asset ${index + 1} must be an object.")
            val name = requiredNonBlankString(item, "name", "Asset ${index + 1} has no name.")
            val category = requiredNonBlankString(
                item,
                "category",
                "Asset ${index + 1} has no category."
            )
            requiredFiniteNumber(item, "quantity", 0.0, Double.MAX_VALUE)
            requiredFiniteNumber(item, "price_toman", 0.0, Double.MAX_VALUE)
            optionalFiniteNumber(item, "average_cost_toman", 0.0, Double.MAX_VALUE)
            optionalFiniteNumber(item, "target_percent", 0.0, 100.0)
            optionalBoolean(item, "include_in_target")

            if (item.has("source") && !item.isNull("source") && item.optJSONObject("source") == null) {
                throw IllegalArgumentException("Asset ${index + 1} source metadata must be an object.")
            }

            val id = item.optString("id", "").trim().lowercase(Locale.US)
            val symbol = item.optString("symbol", "").trim().lowercase(Locale.US)
            val identity = when {
                id.isNotBlank() -> "id:$id"
                symbol.isNotBlank() -> "symbol:${category.lowercase(Locale.US)}:$symbol"
                else -> "name:${category.lowercase(Locale.US)}:${name.lowercase(Locale.US)}"
            }
            require(identities.add(identity)) {
                "Backup contains duplicate asset identity: $name. Local data was not changed."
            }
        }
        return assets.length()
    }

    fun ensureSafeReplacement(localAssetCount: Int, incomingAssetCount: Int) {
        require(localAssetCount <= 0 || incomingAssetCount > 0) {
            "Backup contains no assets, so it was not allowed to replace the current portfolio."
        }
    }

    fun decideSync(
        localFingerprint: String,
        remoteFingerprint: String,
        baselineFingerprint: String?
    ): SyncDecision = when {
        localFingerprint == remoteFingerprint -> SyncDecision.MATCH
        baselineFingerprint.isNullOrBlank() -> SyncDecision.FIRST_SYNC_CONFLICT
        localFingerprint == baselineFingerprint && remoteFingerprint != baselineFingerprint ->
            SyncDecision.LOAD_REMOTE
        localFingerprint != baselineFingerprint && remoteFingerprint == baselineFingerprint ->
            SyncDecision.UPLOAD_LOCAL
        else -> SyncDecision.CONFLICT
    }

    private fun validateLegacyAndroidBackup(root: JSONObject): ValidatedBackup {
        if (root.has("format") && root.optString("format").isNotBlank()) {
            throw IllegalArgumentException("Unsupported backup format. Local data was not changed.")
        }

        val backupVersion = if (root.has("backupVersion")) requiredInt(root, "backupVersion") else 1
        require(backupVersion in 1..3) {
            "Unsupported Android backup version. Local data was not changed."
        }

        val assets = requiredArray(root, "assets")
        require(assets.length() <= MAX_ASSETS) { "Backup contains too many assets." }
        for (index in 0 until assets.length()) {
            val item = assets.optJSONObject(index)
                ?: throw IllegalArgumentException("Asset ${index + 1} must be an object.")
            requiredNonBlankString(item, "name", "Asset ${index + 1} has no name.")
            if (item.has("quantity") || item.has("price")) {
                requiredFiniteNumber(item, "quantity", 0.0, Double.MAX_VALUE)
                requiredFiniteNumber(item, "price", 0.0, Double.MAX_VALUE)
                optionalFiniteNumber(item, "averageCost", 0.0, Double.MAX_VALUE)
                optionalFiniteNumber(item, "targetPercent", 0.0, 100.0)
                optionalBoolean(item, "includeInTarget")
            } else {
                requiredFiniteNumber(item, "amount", 0.0, Double.MAX_VALUE)
            }
        }

        validateAndroidSupplementalPayload(root)
        return ValidatedBackup(
            root = root,
            kind = BackupKind.LEGACY_ANDROID,
            sharedPortfolio = null,
            androidPayload = root,
            incomingAssetCount = assets.length()
        )
    }

    private fun validateAndroidSupplementalPayload(root: JSONObject) {
        optionalObjectArray(root, "transactions", MAX_HISTORY_ROWS)
        optionalObjectArray(root, "snapshots", MAX_HISTORY_ROWS)

        if (root.has("categories")) {
            val categories = requiredArray(root, "categories")
            for (index in 0 until categories.length()) {
                require(categories.opt(index) is String && categories.optString(index).isNotBlank()) {
                    "Category ${index + 1} must be non-empty text."
                }
            }
        }

        optionalFiniteNumber(root, "tolerance", 0.0, 20.0)
        if (root.has("displayUnit")) {
            require(root.optString("displayUnit") in setOf("Toman", "kT", "MT", "Rial")) {
                "Backup contains an unsupported display unit."
            }
        }
        if (root.has("summaryPeriod")) {
            require(root.optString("summaryPeriod") in setOf("Day", "Week", "Month", "Year")) {
                "Backup contains an unsupported summary period."
            }
        }
        if (root.has("autoRefreshMinutes")) {
            require(requiredInt(root, "autoRefreshMinutes") in setOf(0, 5, 15, 30, 60)) {
                "Backup contains an unsupported refresh interval."
            }
        }
    }

    private fun optionalObjectArray(root: JSONObject, key: String, maxRows: Int) {
        if (!root.has(key)) return
        val array = requiredArray(root, key)
        require(array.length() <= maxRows) { "$key contains too many rows." }
        for (index in 0 until array.length()) {
            require(array.optJSONObject(index) != null) { "$key row ${index + 1} must be an object." }
        }
    }

    private fun requiredArray(root: JSONObject, key: String): JSONArray =
        root.optJSONArray(key) ?: throw IllegalArgumentException("Required array '$key' is missing.")

    private fun requiredObject(root: JSONObject, key: String): JSONObject =
        root.optJSONObject(key) ?: throw IllegalArgumentException("Required object '$key' is missing.")

    private fun requiredNonBlankString(root: JSONObject, key: String, message: String): String {
        val value = root.opt(key)
        require(value is String && value.trim().isNotEmpty()) { message }
        return value.trim()
    }

    private fun requiredInt(root: JSONObject, key: String): Int {
        val value = root.opt(key)
        require(value is Number && value.toDouble().isFinite()) { "'$key' must be a number." }
        val double = value.toDouble()
        require(double % 1.0 == 0.0 && double in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            "'$key' must be a whole number."
        }
        return double.toInt()
    }

    private fun requiredFiniteNumber(root: JSONObject, key: String, min: Double, max: Double): Double {
        val value = root.opt(key)
        require(value is Number) { "'$key' must be a number." }
        val number = value.toDouble()
        require(number.isFinite() && number in min..max) { "'$key' is outside the supported range." }
        return number
    }

    private fun optionalFiniteNumber(root: JSONObject, key: String, min: Double, max: Double) {
        if (root.has(key) && !root.isNull(key)) {
            requiredFiniteNumber(root, key, min, max)
        }
    }

    private fun optionalBoolean(root: JSONObject, key: String) {
        if (root.has(key) && !root.isNull(key)) {
            require(root.opt(key) is Boolean) { "'$key' must be true or false." }
        }
    }
}
