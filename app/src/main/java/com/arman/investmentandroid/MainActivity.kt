package com.arman.investmentandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AtomicFile
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    data class Asset(
        val name: String,
        val category: String,
        val quantity: Double,
        val price: Double,
        val averageCost: Double,
        val targetPercent: Double,
        val includeInTarget: Boolean,
        val priceSource: String,
        val symbol: String,
        val sharedId: String = "",
        val sourcePlatform: String = "android",
        val sourceKind: String = "",
        val sourceGroupId: String = "",
        val sourceAssetId: String = "",
        val sourceBankId: String = "",
        val sourceGroupKind: String = ""
    ) {
        val value: Double
            get() = quantity * price

        val invested: Double
            get() = if (category == "Cash") value else quantity * averageCost

        val profit: Double
            get() = if (category == "Cash") 0.0 else value - invested
    }

    data class Transaction(
        val id: String,
        val type: String,
        val assetName: String,
        val quantity: Double,
        val price: Double,
        val realizedProfit: Double,
        val timestamp: Long,
        val beforeAssetJson: String?,
        val afterAssetJson: String?,
        val managed: Boolean
    )

    data class Snapshot(
        val totalValue: Double,
        val timestamp: Long
    )

    private val prefsName = "investment_android_prefs"
    private val assetsKey = "assets_json"
    private val lastValidAssetsKey = "last_valid_assets_json"
    private val transactionsKey = "transactions_json"
    private val snapshotsKey = "snapshots_json"
    private val toleranceKey = "rebalance_tolerance"
    private val lastPriceUpdateKey = "last_price_update"
    private val displayUnitKey = "display_unit"
    private val summaryPeriodKey = "summary_period"
    private val autoRefreshMinutesKey = "auto_refresh_minutes"
    private val categoriesKey = "categories_json"
    private val undoStackKey = "undo_stack_json"
    private val redoStackKey = "redo_stack_json"
    private val appLockHashKey = "app_lock_hash"
    private val cloudBackupUriKey = "cloud_backup_uri"
    private val cloudLastSyncKey = "cloud_last_sync"
    private val cloudSharedFingerprintKey = "cloud_shared_fingerprint"
    private val cloudAutoSyncKey = "cloud_auto_sync"
    private val cloudAutoSyncMinutesKey = "cloud_auto_sync_minutes"
    private val cloudLastAutoCheckKey = "cloud_last_auto_check"
    private val preRestoreBackupKey = "pre_restore_backup_json"
    private val cloudPreWriteFileName = "cloud_prewrite_recovery.json"
    private val uiLanguageKey = "ui_language"
    private val exportBackupRequestCode = 1001
    private val importBackupRequestCode = 1002
    private val corePreviewRequestCode = 1005
    private val corePolicyProposalSaveRequestCode = 1006
    private var pendingCorePolicyProposalJson: String? = null
    private val coreFinancialProposalSaveRequestCode = 1007
    private var pendingCoreFinancialProposalJson: String? = null
    private var pendingCoreFinancialJournalError = false
    private val coreFinancialJournal by lazy {
        PendingCoreFinancialJournal(AndroidCoreFinancialJournalStorage(this))
    }
    private val createCloudBackupRequestCode = 1003
    private val connectCloudBackupRequestCode = 1004
    private val coreCategories = listOf("Cash", "Gold", "Stocks", "Crypto", "Fund", "Other")
    private val priceSources = listOf("Manual", "Nobitex")
    private val displayUnits = listOf("Toman", "kT", "MT", "Rial")
    private val summaryPeriods = listOf("Day", "Week", "Month", "Year")
    private val autoRefreshLabels = listOf("Off", "5 minutes", "15 minutes", "30 minutes", "60 minutes")
    private val autoRefreshValues = listOf(0, 5, 15, 30, 60)
    private val defaultTolerancePercent = 1.0
    private var onPortfolioScreen = false
    private var onPriceCenterScreen = false
    private val autoRefreshHandler = Handler(Looper.getMainLooper())
    private var autoRefreshRunnable: Runnable? = null
    private val cloudSyncHandler = Handler(Looper.getMainLooper())
    private var cloudSyncRunnable: Runnable? = null
    private val cloudExecutor = Executors.newSingleThreadExecutor()
    private val cloudOperationInProgress = AtomicBoolean(false)
    private val priceUpdateInProgress = AtomicBoolean(false)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun uiLanguage(): String =
        getSharedPreferences(prefsName, MODE_PRIVATE).getString(
            uiLanguageKey,
            if (Locale.getDefault().language == "fa") "fa" else "en"
        ) ?: "en"

    private fun ui(value: String): String = UiText.translate(value, uiLanguage())

    override fun attachBaseContext(base: Context) {
        val language = base.getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(uiLanguageKey, null)
            ?: if (Locale.getDefault().language == "fa") "fa" else "en"
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale(language))
        configuration.setLayoutDirection(Locale(language))
        super.attachBaseContext(base.createConfigurationContext(configuration))
    }

    /** Avoid Android 15+ edge-to-edge status/navigation-bar overlap. */
    private fun showContentRespectingSystemBars(content: View) {
        if (Build.VERSION.SDK_INT >= 35) {
            val left = content.paddingLeft
            val top = content.paddingTop
            val right = content.paddingRight
            val bottom = content.paddingBottom
            content.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(left + bars.left, top + bars.top,
                    right + bars.right, bottom + bars.bottom)
                insets
            }
        }
        setContentView(content)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PortfolioAppearance.BACKGROUND
        window.navigationBarColor = PortfolioAppearance.BACKGROUND
        window.decorView.layoutDirection =
            if (uiLanguage() == "fa") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        ensureSeedData()
        try {
            // The on-device journal, not transient Activity state, is authoritative.
            pendingCoreFinancialProposalJson = coreFinancialJournal.load()
        } catch (_: Exception) {
            pendingCoreFinancialJournalError = true
            pendingCoreFinancialProposalJson = null
        }

        when (StartupScreen.destination(
            lockEnabled = isAppLockEnabled(),
            priceCenterRestored = savedInstanceState?.getBoolean("price_center_screen") == true
        )) {
            StartupScreen.Destination.LOCKED -> {
                showLockedScreen()
                showStartupUnlockDialog()
            }
            StartupScreen.Destination.PRICE_CENTER -> showPriceCenterScreen()
            StartupScreen.Destination.PORTFOLIO -> showPortfolioScreen()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("portfolio_screen", onPortfolioScreen)
        outState.putBoolean("price_center_screen", onPriceCenterScreen)
        // Do not copy transaction requests into Android's saved-state Bundle.
        // They are stored in private, non-backed-up atomic app storage.
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        scheduleAutoRefresh()
        scheduleSmartCloudSync()
    }

    override fun onPause() {
        stopAutoRefresh()
        stopSmartCloudSync()
        super.onPause()
    }

    override fun onDestroy() {
        stopAutoRefresh()
        stopSmartCloudSync()
        cloudExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun inferCategory(name: String): String {
        val lower = name.lowercase(Locale.US)
        return when {
            "cash" in lower || "bank" in lower -> "Cash"
            "gold" in lower || "silver" in lower -> "Gold"
            "stock" in lower || "share" in lower -> "Stocks"
            "crypto" in lower || "btc" in lower || "eth" in lower -> "Crypto"
            "fund" in lower || "etf" in lower -> "Fund"
            else -> "Other"
        }
    }

    private fun defaultTargetPercent(name: String, category: String): Double {
        val lower = name.lowercase(Locale.US)
        return when {
            lower == "cash" || category == "Cash" -> 20.0
            lower == "gold" || category == "Gold" -> 30.0
            lower == "stocks" || category == "Stocks" -> 20.0
            lower == "crypto" || category == "Crypto" -> 30.0
            else -> 0.0
        }
    }

    private fun ensureSeedData() {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        if (!prefs.contains(assetsKey)) {
            saveAssets(emptyList())
        }
        if (!prefs.contains(transactionsKey)) {
            saveTransactions(emptyList())
        }
        if (!prefs.contains(snapshotsKey)) {
            saveSnapshots(emptyList())
        }
        if (!prefs.contains(categoriesKey)) {
            saveCategories(coreCategories)
        }
    }

    private fun loadCategories(): MutableList<String> {
        val raw = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(categoriesKey, null)

        if (raw.isNullOrBlank()) {
            return coreCategories.toMutableList()
        }

        return try {
            val array = JSONArray(raw)
            val result = MutableList(array.length()) { index -> array.getString(index) }
            coreCategories.forEach { category ->
                if (!result.contains(category)) {
                    result.add(category)
                }
            }
            result
        } catch (_: Exception) {
            coreCategories.toMutableList()
        }
    }

    private fun saveCategories(categories: List<String>) {
        val unique = mutableListOf<String>()
        categories.forEach { category ->
            val value = category.trim()
            if (value.isNotBlank() && unique.none { it.equals(value, ignoreCase = true) }) {
                unique.add(value)
            }
        }

        coreCategories.forEach { category ->
            if (unique.none { it.equals(category, ignoreCase = true) }) {
                unique.add(category)
            }
        }

        val array = JSONArray()
        unique.forEach { array.put(it) }

        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(categoriesKey, array.toString())
            .apply()
    }

    private fun capturePortfolioState(): JSONObject {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        return JSONObject().apply {
            put("assets", JSONArray(prefs.getString(assetsKey, "[]") ?: "[]"))
            put("transactions", JSONArray(prefs.getString(transactionsKey, "[]") ?: "[]"))
            put("snapshots", JSONArray(prefs.getString(snapshotsKey, "[]") ?: "[]"))
            put("categories", JSONArray(prefs.getString(categoriesKey, "[]") ?: "[]"))
            put("tolerance", loadTolerance())
            put("displayUnit", loadDisplayUnit())
            put("summaryPeriod", loadSummaryPeriod())
            put("autoRefreshMinutes", loadAutoRefreshMinutes())
        }
    }

    private fun loadStateStack(key: String): MutableList<JSONObject> {
        val raw = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(key, "[]") ?: "[]"

        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index -> array.getJSONObject(index) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveStateStack(key: String, states: List<JSONObject>) {
        val array = JSONArray()
        states.takeLast(10).forEach { array.put(it) }
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(key, array.toString())
            .apply()
    }

    private fun pushUndoCheckpoint() {
        val undo = loadStateStack(undoStackKey)
        undo.add(capturePortfolioState())
        saveStateStack(undoStackKey, undo)
        saveStateStack(redoStackKey, emptyList())
    }

    private fun restorePortfolioState(state: JSONObject) {
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(assetsKey, state.optJSONArray("assets")?.toString() ?: "[]")
            .putString(transactionsKey, state.optJSONArray("transactions")?.toString() ?: "[]")
            .putString(snapshotsKey, state.optJSONArray("snapshots")?.toString() ?: "[]")
            .putString(
                categoriesKey,
                state.optJSONArray("categories")?.toString()
                    ?: JSONArray(coreCategories).toString()
            )
            .putString(
                toleranceKey,
                state.optDouble("tolerance", defaultTolerancePercent).toString()
            )
            .putString(displayUnitKey, state.optString("displayUnit", loadDisplayUnit()))
            .putString(summaryPeriodKey, state.optString("summaryPeriod", loadSummaryPeriod()))
            .putInt(autoRefreshMinutesKey, state.optInt("autoRefreshMinutes", loadAutoRefreshMinutes()))
            .apply()
    }

    private fun preservePreRestoreState() {
        val saved = getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(preRestoreBackupKey, capturePortfolioState().toString())
            .commit()
        check(saved) { "Could not preserve the current local portfolio before restore." }
    }

    private fun restorePreviousLocalState() {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val raw = prefs.getString(preRestoreBackupKey, null)
            ?: throw IllegalStateException("No previous local portfolio is available.")
        val previous = JSONObject(raw)
        val current = capturePortfolioState().toString()
        restorePortfolioState(previous)
        prefs.edit().putString(preRestoreBackupKey, current).apply()
        scheduleAutoRefresh()
    }

    private fun undoLastChange() {
        val undo = loadStateStack(undoStackKey)
        if (undo.isEmpty()) {
            Toast.makeText(this, ui("Nothing to undo."), Toast.LENGTH_SHORT).show()
            return
        }

        val redo = loadStateStack(redoStackKey)
        redo.add(capturePortfolioState())
        val previous = undo.removeAt(undo.lastIndex)

        saveStateStack(undoStackKey, undo)
        saveStateStack(redoStackKey, redo)
        restorePortfolioState(previous)
        showPortfolioScreen()
        Toast.makeText(this, ui("Change undone."), Toast.LENGTH_SHORT).show()
    }

    private fun redoLastChange() {
        val redo = loadStateStack(redoStackKey)
        if (redo.isEmpty()) {
            Toast.makeText(this, ui("Nothing to redo."), Toast.LENGTH_SHORT).show()
            return
        }

        val undo = loadStateStack(undoStackKey)
        undo.add(capturePortfolioState())
        val next = redo.removeAt(redo.lastIndex)

        saveStateStack(undoStackKey, undo)
        saveStateStack(redoStackKey, redo)
        restorePortfolioState(next)
        showPortfolioScreen()
        Toast.makeText(this, ui("Change restored."), Toast.LENGTH_SHORT).show()
    }

    private fun loadAssets(): MutableList<Asset> {
        val raw = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(assetsKey, "[]") ?: "[]"

        val assets = mutableListOf<Asset>()
        var migratedLegacyData = false

        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)

                val name = item.optString("name", "Asset")
                val category = if (item.has("category")) {
                    item.optString("category", "Other")
                } else {
                    migratedLegacyData = true
                    inferCategory(name)
                }

                when {
                    item.has("quantity") && item.has("price") -> {
                        val quantity = item.optDouble("quantity", Double.NaN)
                        val price = item.optDouble("price", 0.0)
                        val averageCost = if (item.has("averageCost")) {
                            item.optDouble("averageCost", price)
                        } else {
                            migratedLegacyData = true
                            price
                        }

                        val targetPercent = if (item.has("targetPercent")) {
                            item.optDouble("targetPercent", 0.0)
                        } else {
                            migratedLegacyData = true
                            defaultTargetPercent(name, category)
                        }

                        val includeInTarget = if (item.has("includeInTarget")) {
                            item.optBoolean("includeInTarget", true)
                        } else {
                            migratedLegacyData = true
                            true
                        }

                        val priceSource = if (item.has("priceSource")) {
                            item.optString("priceSource", "Manual")
                        } else {
                            migratedLegacyData = true
                            "Manual"
                        }

                        val symbol = if (item.has("symbol")) {
                            item.optString("symbol", "")
                        } else {
                            migratedLegacyData = true
                            ""
                        }

                        require(
                            quantity.isFinite() && quantity >= 0.0 &&
                                price.isFinite() && price >= 0.0 &&
                                averageCost.isFinite() && averageCost >= 0.0 &&
                                targetPercent.isFinite() && targetPercent in 0.0..100.0
                        ) { "Stored asset contains invalid numeric data." }

                        assets.add(
                            Asset(
                                name = name,
                                category = category,
                                quantity = quantity,
                                price = price,
                                averageCost = averageCost,
                                targetPercent = targetPercent,
                                includeInTarget = includeInTarget,
                                priceSource = priceSource,
                                symbol = symbol,
                                sharedId = item.optString("sharedId", ""),
                                sourcePlatform = item.optString("sourcePlatform", "android"),
                                sourceKind = item.optString("sourceKind", ""),
                                sourceGroupId = item.optString("sourceGroupId", ""),
                                sourceAssetId = item.optString("sourceAssetId", ""),
                                sourceBankId = item.optString("sourceBankId", ""),
                                sourceGroupKind = item.optString("sourceGroupKind", "")
                            )
                        )
                    }

                    else -> {
                        val legacyAmount = item.optDouble("amount", 0.0)
                        require(legacyAmount.isFinite() && legacyAmount >= 0.0) {
                            "Stored legacy asset contains invalid numeric data."
                        }
                        assets.add(
                            Asset(
                                name = name,
                                category = category,
                                quantity = 1.0,
                                price = legacyAmount,
                                averageCost = legacyAmount,
                                targetPercent = defaultTargetPercent(name, category),
                                includeInTarget = true,
                                priceSource = "Manual",
                                symbol = ""
                            )
                        )
                        migratedLegacyData = true
                    }
                }
            }
        } catch (_: Exception) {
            val recovered = getSharedPreferences(prefsName, MODE_PRIVATE)
                .getString(lastValidAssetsKey, null)
            if (!recovered.isNullOrBlank() && recovered != raw && isValidLocalAssetsJson(recovered)) {
                val restored = getSharedPreferences(prefsName, MODE_PRIVATE)
                    .edit()
                    .putString(assetsKey, recovered)
                    .commit()
                if (restored) return loadAssets()
            }
            return mutableListOf()
        }

        if (migratedLegacyData) {
            saveAssets(assets)
        }

        return assets
    }

    private fun assetsToJsonArray(assets: List<Asset>): JSONArray {
        val array = JSONArray()
        assets.forEach { asset ->
            array.put(
                JSONObject().apply {
                    put("name", asset.name)
                    put("category", canonicalSharedCategory(JSONObject().apply {
                        put("category", asset.category)
                    }))
                    put("quantity", asset.quantity)
                    put("price", asset.price)
                    put("averageCost", asset.averageCost)
                    put("targetPercent", asset.targetPercent)
                    put("includeInTarget", asset.includeInTarget)
                    put("priceSource", asset.priceSource)
                    put("symbol", asset.symbol)
                    put("sharedId", asset.sharedId)
                    put("sourcePlatform", asset.sourcePlatform)
                    put("sourceKind", asset.sourceKind)
                    put("sourceGroupId", asset.sourceGroupId)
                    put("sourceAssetId", asset.sourceAssetId)
                    put("sourceBankId", asset.sourceBankId)
                    put("sourceGroupKind", asset.sourceGroupKind)
                }
            )
        }

        return array
    }

    private fun isValidLocalAssetsJson(raw: String): Boolean {
        return try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: return false
                if (item.optString("name", "").isBlank()) return false
                val hasModernNumbers = item.has("quantity") && item.has("price")
                val hasLegacyAmount = item.has("amount")
                if (!hasModernNumbers && !hasLegacyAmount) return false
                val values = if (hasModernNumbers) {
                    listOf(item.opt("quantity"), item.opt("price"))
                } else {
                    listOf(item.opt("amount"))
                }
                if (values.any { it !is Number || !it.toDouble().isFinite() || it.toDouble() < 0.0 }) {
                    return false
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun saveAssets(assets: List<Asset>) {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val next = assetsToJsonArray(assets).toString()
        val editor = prefs.edit().putString(assetsKey, next)
        val current = prefs.getString(assetsKey, null)
        if (!current.isNullOrBlank() && current != next && isValidLocalAssetsJson(current)) {
            editor.putString(lastValidAssetsKey, current)
        }
        editor.apply()
    }

    private fun assetToJson(asset: Asset): String {
        return JSONObject().apply {
            put("name", asset.name)
            put("category", asset.category)
            put("quantity", asset.quantity)
            put("price", asset.price)
            put("averageCost", asset.averageCost)
            put("targetPercent", asset.targetPercent)
            put("includeInTarget", asset.includeInTarget)
            put("priceSource", asset.priceSource)
            put("symbol", asset.symbol)
            put("sharedId", asset.sharedId)
            put("sourcePlatform", asset.sourcePlatform)
            put("sourceKind", asset.sourceKind)
            put("sourceGroupId", asset.sourceGroupId)
            put("sourceAssetId", asset.sourceAssetId)
            put("sourceBankId", asset.sourceBankId)
            put("sourceGroupKind", asset.sourceGroupKind)
        }.toString()
    }

    private fun assetFromJson(raw: String?): Asset? {
        if (raw.isNullOrBlank()) {
            return null
        }

        return try {
            val item = JSONObject(raw)
            Asset(
                name = item.getString("name"),
                category = item.optString("category", "Other"),
                quantity = item.optDouble("quantity", 0.0),
                price = item.optDouble("price", 0.0),
                averageCost = item.optDouble("averageCost", 0.0),
                targetPercent = item.optDouble("targetPercent", 0.0),
                includeInTarget = item.optBoolean("includeInTarget", true),
                priceSource = item.optString("priceSource", "Manual"),
                symbol = item.optString("symbol", ""),
                sharedId = item.optString("sharedId", ""),
                sourcePlatform = item.optString("sourcePlatform", "android"),
                sourceKind = item.optString("sourceKind", ""),
                sourceGroupId = item.optString("sourceGroupId", ""),
                sourceAssetId = item.optString("sourceAssetId", ""),
                sourceBankId = item.optString("sourceBankId", ""),
                sourceGroupKind = item.optString("sourceGroupKind", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun assetsEquivalent(left: Asset, right: Asset): Boolean {
        fun close(a: Double, b: Double): Boolean =
            kotlin.math.abs(a - b) <= kotlin.math.max(0.000001, kotlin.math.abs(b) * 0.0000001)

        return left.name == right.name &&
            left.category == right.category &&
            close(left.quantity, right.quantity) &&
            close(left.price, right.price) &&
            close(left.averageCost, right.averageCost) &&
            close(left.targetPercent, right.targetPercent) &&
            left.includeInTarget == right.includeInTarget &&
            left.priceSource == right.priceSource &&
            left.symbol == right.symbol
    }

    private fun loadTransactions(): MutableList<Transaction> {
        val raw = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(transactionsKey, "[]") ?: "[]"

        val transactions = mutableListOf<Transaction>()
        var migrated = false

        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val timestamp = item.optLong("timestamp", System.currentTimeMillis())
                val id = if (item.has("id")) {
                    item.optString("id")
                } else {
                    migrated = true
                    "legacy-" + timestamp + "-" + index
                }

                transactions.add(
                    Transaction(
                        id = id,
                        type = item.optString("type", "BUY"),
                        assetName = item.optString("assetName", "Asset"),
                        quantity = item.optDouble("quantity", 0.0),
                        price = item.optDouble("price", 0.0),
                        realizedProfit = item.optDouble("realizedProfit", 0.0),
                        timestamp = timestamp,
                        beforeAssetJson = if (item.has("beforeAssetJson") && !item.isNull("beforeAssetJson")) {
                            item.optString("beforeAssetJson")
                        } else {
                            null
                        },
                        afterAssetJson = if (item.has("afterAssetJson") && !item.isNull("afterAssetJson")) {
                            item.optString("afterAssetJson")
                        } else {
                            null
                        },
                        managed = item.optBoolean("managed", false)
                    )
                )

                if (!item.has("managed")) {
                    migrated = true
                }
            }
        } catch (_: Exception) {
            return mutableListOf()
        }

        if (migrated) {
            saveTransactions(transactions)
        }

        return transactions
    }

    private fun saveTransactions(transactions: List<Transaction>) {
        val array = JSONArray()

        transactions.forEach { transaction ->
            array.put(
                JSONObject().apply {
                    put("id", transaction.id)
                    put("type", transaction.type)
                    put("assetName", transaction.assetName)
                    put("quantity", transaction.quantity)
                    put("price", transaction.price)
                    put("realizedProfit", transaction.realizedProfit)
                    put("timestamp", transaction.timestamp)
                    if (transaction.beforeAssetJson == null) {
                        put("beforeAssetJson", JSONObject.NULL)
                    } else {
                        put("beforeAssetJson", transaction.beforeAssetJson)
                    }
                    if (transaction.afterAssetJson == null) {
                        put("afterAssetJson", JSONObject.NULL)
                    } else {
                        put("afterAssetJson", transaction.afterAssetJson)
                    }
                    put("managed", transaction.managed)
                }
            )
        }

        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(transactionsKey, array.toString())
            .apply()
    }

    private fun loadSnapshots(): MutableList<Snapshot> {
        val raw = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(snapshotsKey, "[]") ?: "[]"

        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                Snapshot(
                    totalValue = item.optDouble("totalValue", 0.0),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis())
                )
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveSnapshots(snapshots: List<Snapshot>) {
        val array = JSONArray()
        snapshots.forEach { snapshot ->
            array.put(
                JSONObject().apply {
                    put("totalValue", snapshot.totalValue)
                    put("timestamp", snapshot.timestamp)
                }
            )
        }

        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(snapshotsKey, array.toString())
            .apply()
    }

    private fun recordSnapshot(assets: List<Asset>) {
        val snapshots = loadSnapshots()
        snapshots.add(
            Snapshot(
                totalValue = assets.sumOf { it.value },
                timestamp = System.currentTimeMillis()
            )
        )
        saveSnapshots(snapshots)
    }

    private fun formatToman(value: Double): String {
        val unit = loadDisplayUnit()
        val scaledValue: Double
        val suffix: String
        val decimals: Int

        when (unit) {
            "kT" -> {
                scaledValue = value / 1_000.0
                suffix = " kT"
                decimals = 1
            }

            "MT" -> {
                scaledValue = value / 1_000_000.0
                suffix = " MT"
                decimals = 2
            }

            "Rial" -> {
                scaledValue = value * 10.0
                suffix = " " + ui("Rial")
                decimals = 0
            }

            else -> {
                scaledValue = value
                suffix = " " + ui("Toman")
                decimals = 0
            }
        }

        val formatter = NumberFormat.getNumberInstance(
            if (uiLanguage() == "fa") Locale("fa", "IR") else Locale.US
        ).apply {
            maximumFractionDigits = decimals
            minimumFractionDigits = 0
        }
        return formatter.format(scaledValue) + suffix
    }

    private fun formatSignedToman(value: Double): String {
        val prefix = if (value > 0.0) "+" else ""
        return prefix + formatToman(value)
    }

    private fun formatQuantity(value: Double): String {
        return NumberFormat.getNumberInstance(Locale.US).apply {
            maximumFractionDigits = 6
            minimumFractionDigits = 0
            isGroupingUsed = true
        }.format(value)
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))
    }

    private fun loadTolerance(): Double {
        val raw = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(toleranceKey, null)
        return raw?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.0..20.0 }
            ?: defaultTolerancePercent
    }

    private fun saveTolerance(value: Double) {
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(toleranceKey, value.toString())
            .apply()
    }

    private fun markPriceUpdate() {
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putLong(lastPriceUpdateKey, System.currentTimeMillis())
            .apply()
    }

    private fun loadLastPriceUpdate(): Long {
        return getSharedPreferences(prefsName, MODE_PRIVATE)
            .getLong(lastPriceUpdateKey, 0L)
    }

    private fun loadDisplayUnit(): String {
        return getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(displayUnitKey, "Toman") ?: "Toman"
    }

    private fun loadSummaryPeriod(): String {
        return getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(summaryPeriodKey, "Month") ?: "Month"
    }

    private fun loadAutoRefreshMinutes(): Int {
        return getSharedPreferences(prefsName, MODE_PRIVATE)
            .getInt(autoRefreshMinutesKey, 0)
    }

    private fun saveSettings(displayUnit: String, summaryPeriod: String, autoRefreshMinutes: Int) {
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(displayUnitKey, displayUnit)
            .putString(summaryPeriodKey, summaryPeriod)
            .putInt(autoRefreshMinutesKey, autoRefreshMinutes)
            .apply()
    }

    private fun stopAutoRefresh() {
        autoRefreshRunnable?.let { autoRefreshHandler.removeCallbacks(it) }
        autoRefreshRunnable = null
    }

    private fun scheduleAutoRefresh() {
        stopAutoRefresh()
        val minutes = loadAutoRefreshMinutes()
        if (minutes <= 0) {
            return
        }

        val delay = minutes * 60_000L
        val runnable = object : Runnable {
            override fun run() {
                updateNobitexPrices(showResult = false)
                autoRefreshHandler.postDelayed(this, delay)
            }
        }

        autoRefreshRunnable = runnable
        autoRefreshHandler.postDelayed(runnable, delay)
    }

    private fun periodStartMillis(period: String): Long {
        val duration = when (period) {
            "Day" -> 24L * 60L * 60L * 1000L
            "Week" -> 7L * 24L * 60L * 60L * 1000L
            "Year" -> 365L * 24L * 60L * 60L * 1000L
            else -> 30L * 24L * 60L * 60L * 1000L
        }
        return System.currentTimeMillis() - duration
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun isAppLockEnabled(): Boolean {
        return !getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(appLockHashKey, null)
            .isNullOrBlank()
    }

    private fun verifyPin(pin: String): Boolean {
        val stored = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(appLockHashKey, null)
            ?: return false
        return hashPin(pin) == stored
    }

    private fun savePin(pin: String) {
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(appLockHashKey, hashPin(pin))
            .apply()
    }

    private fun removePin() {
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .remove(appLockHashKey)
            .apply()
    }

    private fun showLockedScreen() {
        onPortfolioScreen = false
        onPriceCenterScreen = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(PortfolioAppearance.BACKGROUND)
        }

        root.addView(
            TextView(this).apply {
                text = ui("Investment Android")
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
            }
        )

        root.addView(
            TextView(this).apply {
                text = ui("App Locked")
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                setPadding(0, dp(12), 0, 0)
            }
        )

        showContentRespectingSystemBars(root)
    }

    private fun pinInput(): EditText {
        return EditText(this).apply {
            hint = ui("4–8 digit PIN")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(20), dp(8), dp(20), 0)
        }
    }

    private fun showStartupUnlockDialog() {
        val input = pinInput()
        val dialog = AlertDialog.Builder(this)
            .setTitle(ui("Unlock Investment"))
            .setMessage(ui("Enter your app PIN."))
            .setView(input)
            .setPositiveButton(ui("Unlock"), null)
            .create()

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (verifyPin(pin)) {
                    dialog.dismiss()
                    showPortfolioScreen()
                } else {
                    input.error = ui("Incorrect PIN")
                    input.selectAll()
                }
            }
        }

        dialog.show()
    }

    private fun showSetPinDialog(afterSave: (() -> Unit)? = null) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val pin = pinInput()
        val confirm = pinInput().apply {
            hint = ui("Confirm PIN")
        }
        form.addView(pin)
        form.addView(confirm)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isAppLockEnabled()) "Change App PIN" else "Enable App Lock")
            .setMessage(ui("Use a 4–8 digit PIN. The PIN itself is not stored."))
            .setView(form)
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Save"), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = pin.text.toString()
                val confirmation = confirm.text.toString()

                when {
                    value.length !in 4..8 || value.any { !it.isDigit() } ->
                        pin.error = ui("PIN must contain 4–8 digits")
                    value != confirmation ->
                        confirm.error = ui("PINs do not match")
                    else -> {
                        savePin(value)
                        dialog.dismiss()
                        Toast.makeText(this, ui("App lock enabled."), Toast.LENGTH_SHORT).show()
                        afterSave?.invoke()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun verifyCurrentPinThen(action: () -> Unit) {
        val input = pinInput()
        val dialog = AlertDialog.Builder(this)
            .setTitle(ui("Verify Current PIN"))
            .setView(input)
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Continue"), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (verifyPin(input.text.toString())) {
                    dialog.dismiss()
                    action()
                } else {
                    input.error = ui("Incorrect PIN")
                    input.selectAll()
                }
            }
        }

        dialog.show()
    }

    private fun showAppLockDialog() {
        if (!isAppLockEnabled()) {
            showSetPinDialog()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(ui("App Lock"))
            .setMessage(ui("App lock is enabled."))
            .setItems(arrayOf(ui("Change PIN"), ui("Remove App Lock"))) { _, which ->
                when (which) {
                    0 -> verifyCurrentPinThen { showSetPinDialog() }
                    1 -> verifyCurrentPinThen {
                        removePin()
                        Toast.makeText(this, ui("App lock removed."), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(ui("Close"), null)
            .show()
    }

    private fun buildPrivacySafeAiSummary(): String {
        val assets = loadAssets()
        val totalValue = assets.sumOf { it.value }
        val targetAssets = assets.filter { it.includeInTarget }
        val targetValue = targetAssets.sumOf { it.value }

        return buildString {
            append("Investment portfolio summary for AI analysis\n")
            append("Privacy mode: no balances, quantities, purchase amounts, or personal identifiers included.\n")
            append("Please analyze allocation risk and suggest target-allocation changes if justified by current market conditions.\n\n")

            assets.sortedByDescending { it.value }.forEach { asset ->
                val portfolioPercent = if (totalValue > 0.0) {
                    asset.value / totalValue * 100.0
                } else {
                    0.0
                }

                val targetPoolPercent = if (asset.includeInTarget && targetValue > 0.0) {
                    asset.value / targetValue * 100.0
                } else {
                    0.0
                }

                val profitPercent = if (asset.category != "Cash" && asset.averageCost > 0.0) {
                    (asset.price / asset.averageCost - 1.0) * 100.0
                } else {
                    0.0
                }

                append("- ")
                append(asset.name)
                append(" | ")
                append(asset.category)
                append(" | portfolio ")
                append(String.format(Locale.US, "%.1f%%", portfolioPercent))

                if (asset.includeInTarget) {
                    append(" | target-pool ")
                    append(String.format(Locale.US, "%.1f%%", targetPoolPercent))
                    append(" | target ")
                    append(String.format(Locale.US, "%.1f%%", asset.targetPercent))
                } else {
                    append(" | target excluded")
                }

                if (asset.category != "Cash") {
                    append(" | unrealized P/L ")
                    append(String.format(Locale.US, "%+.1f%%", profitPercent))
                }

                if (asset.symbol.isNotBlank()) {
                    append(" | symbol ")
                    append(asset.symbol)
                }

                append("\n")
            }

            append("\nRebalance tolerance: ")
            append(String.format(Locale.US, "±%.1f%%", loadTolerance()))
        }
    }

    private fun sharePrivacySafeAiSummary() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Investment Portfolio AI Summary")
            putExtra(Intent.EXTRA_TEXT, buildPrivacySafeAiSummary())
        }

        startActivity(Intent.createChooser(intent, "Share AI Portfolio Summary"))
    }

    private fun showPortfolioScreen() {
        onPortfolioScreen = true
        onPriceCenterScreen = false
        val assets = loadAssets()
        val totalValue = assets.sumOf { it.value }
        val totalInvested = assets.sumOf { it.invested }
        val totalProfit = totalValue - totalInvested
        val targetAssets = assets.filter { it.includeInTarget }
        val targetPortfolioValue = targetAssets.sumOf { it.value }
        val totalTarget = targetAssets.sumOf { it.targetPercent }
        val tolerance = loadTolerance()
        val snapshots = loadSnapshots()
        val snapshotChange = if (snapshots.size >= 2) {
            snapshots.last().totalValue - snapshots[snapshots.lastIndex - 1].totalValue
        } else {
            null
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(PortfolioAppearance.BACKGROUND)
        }

        container.addView(
            TextView(this).apply {
                text = ui("My Portfolio")
                textSize = DashboardPresentation.titleSizeSp(uiLanguage() == "fa")
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
            }
        )

        addOverviewCard(
            parent = container,
            assets = assets,
            totalValue = totalValue,
            totalInvested = totalInvested,
            totalProfit = totalProfit,
            targetPortfolioValue = targetPortfolioValue,
            totalTarget = totalTarget,
            tolerance = tolerance,
            snapshotChange = snapshotChange
        )

        addCloudSyncCard(container)

        if (!DashboardPresentation.hasAssets(assets.size)) {
            container.addView(
                TextView(this).apply {
                    text = ui("No assets yet. Tap Add Asset to create your first one.")
                    textSize = 14f
                    setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                    setPadding(0, dp(12), 0, dp(2))
                }
            )
        } else {
            addCategorySummary(container, assets, totalValue)
            addPeriodSummary(container, loadSummaryPeriod())
            addGroupedHoldings(
                parent = container,
                assets = assets,
                totalValue = totalValue,
                targetPortfolioValue = targetPortfolioValue,
                tolerance = tolerance
            )
            addRebalanceSummary(
                container, targetAssets, targetPortfolioValue, totalTarget, tolerance
            )
        }

        val addButton = Button(this).apply {
            text = ui("+ Add Asset")
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showAssetDialog() }
        }

        val priceCenterButton = Button(this).apply {
            text = ui("Price Center")
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showPriceCenterScreen() }
        }

        val targetsButton = Button(this).apply {
            text = ui("Edit Targets")
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showTargetsDialog() }
        }

        val toleranceButton = Button(this).apply {
            text = String.format(Locale.US, ui("Tolerance: ±%.1f%%"), tolerance)
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showToleranceDialog() }
        }

        val activityButton = Button(this).apply {
            text = ui("Activity")
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showActivityDialog() }
        }

        val moreToolsButton = Button(this).apply {
            text = ui("More Tools")
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showToolsDialog() }
        }

        val historyButton = Button(this).apply {
            text = ui("Portfolio History")
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showHistoryDialog() }
        }

        val categoriesButton = Button(this).apply {
            text = ui("Manage Categories")
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showCategoryManagerDialog() }
        }

        val undoButton = Button(this).apply {
            text = ui("Undo")
            isAllCaps = false
            textSize = 16f
            isEnabled = loadStateStack(undoStackKey).isNotEmpty()
            setOnClickListener { undoLastChange() }
        }

        val redoButton = Button(this).apply {
            text = ui("Redo")
            isAllCaps = false
            textSize = 16f
            isEnabled = loadStateStack(redoStackKey).isNotEmpty()
            setOnClickListener { redoLastChange() }
        }

        val settingsButton = Button(this).apply {
            text = ui("Settings")
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showSettingsDialog() }
        }

        val backupButton = Button(this).apply {
            text = ui("Backup / Restore")
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showBackupDialog() }
        }

        val resetButton = Button(this).apply {
            text = ui("Reset Portfolio")
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(ui("Reset portfolio?"))
                    .setMessage(ui("This will delete all assets and transaction history. You can undo it afterward."))
                    .setNegativeButton(ui("Cancel"), null)
                    .setPositiveButton(ui("Reset")) { _, _ ->
                        pushUndoCheckpoint()
                        saveAssets(emptyList())
                        saveTransactions(emptyList())
                        saveSnapshots(emptyList())
                        showPortfolioScreen()
                    }
                    .show()
            }
        }

        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }

        container.addView(
            TextView(this).apply {
                text = ui("Quick Actions")
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
                setPadding(0, dp(13), 0, dp(4))
            }
        )

        val primaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        primaryRow.addView(
            addButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(5)
            }
        )
        primaryRow.addView(
            priceCenterButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(5)
            }
        )
        container.addView(primaryRow, buttonParams)

        val secondaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        secondaryRow.addView(
            activityButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(5)
            }
        )
        secondaryRow.addView(
            moreToolsButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(5)
            }
        )
        container.addView(secondaryRow, buttonParams)

        val undoRedoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        undoRedoRow.addView(
            undoButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(5)
            }
        )
        undoRedoRow.addView(
            redoButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(5)
            }
        )
        container.addView(undoRedoRow, buttonParams)

        container.addView(
            TextView(this).apply {
                text = ui("Investment Android • v${BuildConfig.VERSION_NAME}")
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                setPadding(0, dp(18), 0, dp(4))
            }
        )

        showContentRespectingSystemBars(
            ScrollView(this).apply {
                setBackgroundColor(PortfolioAppearance.BACKGROUND)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                addView(container)
            }
        )
    }

    private fun localCloudSyncState(): String {
        val uri = loadCloudBackupUri()
            ?: return "Not connected"

        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val baseline = prefs.getString(cloudSharedFingerprintKey, null)
        if (baseline.isNullOrBlank()) {
            return "Connected • first sync pending"
        }

        return try {
            val current = sharedFingerprint(buildSharedPortfolio())
            if (current == baseline) {
                "Last known synced"
            } else {
                "Local changes pending sync"
            }
        } catch (_: Exception) {
            "Connected"
        }
    }

    private fun addCloudSyncCard(parent: LinearLayout) {
        val connected = loadCloudBackupUri() != null
        val lastSync = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getLong(cloudLastSyncKey, 0L)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(PortfolioAppearance.SURFACE)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), PortfolioAppearance.BORDER)
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        textBlock.addView(
            TextView(this).apply {
                text = ui("Cloud Sync")
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
            }
        )

        textBlock.addView(
            TextView(this).apply {
                text = buildString {
                    append(ui(localCloudSyncState()))
                    append(
                        if (isCloudAutoSyncEnabled()) {
                            ui(" • Smart sync ") + loadCloudAutoSyncMinutes() + ui("m")
                        } else {
                            ui(" • Smart sync off")
                        }
                    )
                    if (lastSync > 0L) {
                        append(" • ")
                        append(formatDate(lastSync))
                    }
                }
                textSize = 12f
                setTextColor(
                    when (localCloudSyncState()) {
                        "Last known synced" -> PortfolioAppearance.SUCCESS
                        "Local changes pending sync" -> PortfolioAppearance.WARNING
                        else -> PortfolioAppearance.TEXT_SECONDARY
                    }
                )
            }
        )

        headerRow.addView(
            textBlock,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        headerRow.addView(
            Button(this).apply {
                text = ui(if (connected) "Sync Now" else "Connect")
                isAllCaps = false
                textSize = 13f
                setOnClickListener {
                    if (connected) {
                        syncToCloud()
                    } else {
                        showCloudBackupDialog()
                    }
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        card.addView(headerRow)
        parent.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            }
        )
    }

    private fun addOverviewCard(
        parent: LinearLayout,
        assets: List<Asset>,
        totalValue: Double,
        totalInvested: Double,
        totalProfit: Double,
        targetPortfolioValue: Double,
        totalTarget: Double,
        tolerance: Double,
        snapshotChange: Double?
    ) {
        val targetValid = kotlin.math.abs(totalTarget - 100.0) <= 0.01
        val needAttention = if (targetValid && targetPortfolioValue > 0.0) {
            assets.count { asset ->
                if (!asset.includeInTarget || asset.targetPercent <= 0.0) {
                    false
                } else {
                    val current = asset.value / targetPortfolioValue * 100.0
                    kotlin.math.abs(current - asset.targetPercent) > tolerance
                }
            }
        } else {
            0
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(PortfolioAppearance.HERO_START, PortfolioAppearance.HERO_END)
            ).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), PortfolioAppearance.BORDER)
            }
        }

        card.addView(
            TextView(this).apply {
                text = ui("Total Portfolio Value")
                textSize = 13f
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
            }
        )

        card.addView(
            TextView(this).apply {
                text = formatToman(totalValue)
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
                setPadding(0, dp(2), 0, dp(10))
            }
        )

        card.addView(
            TextView(this).apply {
                text = ui("Invested: ") + formatToman(totalInvested)
                textSize = 13f
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
            }
        )

        card.addView(
            TextView(this).apply {
                text = ui("Unrealized P/L: ") + formatSignedToman(totalProfit)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    when {
                        totalProfit > 0.0 -> PortfolioAppearance.SUCCESS
                        totalProfit < 0.0 -> PortfolioAppearance.ERROR
                        else -> PortfolioAppearance.TEXT_SECONDARY
                    }
                )
                setPadding(0, dp(2), 0, dp(8))
            }
        )

        if (snapshotChange != null) {
            card.addView(
                TextView(this).apply {
                    text = ui("Since previous snapshot: ") + formatSignedToman(snapshotChange)
                    textSize = 13f
                    setTextColor(
                        when {
                            snapshotChange > 0.0 -> PortfolioAppearance.SUCCESS
                            snapshotChange < 0.0 -> PortfolioAppearance.ERROR
                            else -> PortfolioAppearance.TEXT_SECONDARY
                        }
                    )
                    setPadding(0, 0, 0, dp(6))
                }
            )
        }

        if (DashboardPresentation.hasAssets(assets.size)) {
        val lastPriceUpdate = loadLastPriceUpdate()
        card.addView(
            TextView(this).apply {
                text = if (lastPriceUpdate > 0L) {
                    ui("Prices updated: ") + formatDate(lastPriceUpdate)
                } else {
                    ui("Prices have not been updated yet.")
                }
                textSize = 12f
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
            }
        )

        card.addView(
            TextView(this).apply {
                text = when {
                    !targetValid -> String.format(
                        Locale.US,
                        ui("Portfolio Health: Fix targets (total %.1f%%)"),
                        totalTarget
                    )
                    needAttention == 0 -> ui("Portfolio Health: On target")
                    else -> ui("Portfolio Health: ") + needAttention + ui(" asset(s) need attention")
                }
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    when {
                        !targetValid -> PortfolioAppearance.WARNING
                        needAttention == 0 -> PortfolioAppearance.SUCCESS
                        else -> PortfolioAppearance.WARNING
                    }
                )
                setPadding(0, dp(7), 0, 0)
            }
        )
        }

        parent.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(4)
            }
        )
    }

    private fun addCategorySummary(
        parent: LinearLayout,
        assets: List<Asset>,
        totalValue: Double
    ) {
        parent.addView(
            TextView(this).apply {
                text = ui("Category Breakdown")
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
                setPadding(0, dp(14), 0, dp(6))
            }
        )

        if (assets.isEmpty()) {
            return
        }

        assets.groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.value } }
            .toList()
            .sortedByDescending { it.second }
            .forEach { (category, value) ->
                val allocation = if (totalValue > 0.0) value / totalValue * 100.0 else 0.0
                parent.addView(
                    TextView(this).apply {
                        text = String.format(
                            Locale.US,
                            "%s  •  %.1f%%  •  %s",
                            ui(category),
                            allocation,
                            formatToman(value)
                        )
                        textSize = 13f
                        setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = GradientDrawable().apply {
                            setColor(PortfolioAppearance.SURFACE)
                            cornerRadius = dp(10).toFloat()
                            setStroke(dp(1), PortfolioAppearance.BORDER)
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(5)
                    }
                )
            }
    }

    private fun addGroupedHoldings(
        parent: LinearLayout,
        assets: List<Asset>,
        totalValue: Double,
        targetPortfolioValue: Double,
        tolerance: Double
    ) {
        parent.addView(
            TextView(this).apply {
                text = ui("Holdings")
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
                setPadding(0, dp(18), 0, dp(8))
            }
        )

        val indexedAssets = assets.withIndex().toList()
        val grouped = indexedAssets
            .groupBy { it.value.category }
            .toList()
            .sortedByDescending { entry -> entry.second.sumOf { it.value.value } }

        grouped.forEach { (category, categoryAssets) ->
            val categoryValue = categoryAssets.sumOf { it.value.value }
            val categoryAllocation = if (totalValue > 0.0) categoryValue / totalValue * 100.0 else 0.0

            parent.addView(
                TextView(this).apply {
                    text = String.format(
                        Locale.US,
                        "%s  •  %.1f%%  •  %s",
                        ui(category),
                        categoryAllocation,
                        formatToman(categoryValue)
                    )
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                    setPadding(0, dp(8), 0, dp(7))
                }
            )

            categoryAssets
                .sortedByDescending { it.value.value }
                .forEach { indexedAsset ->
                    val asset = indexedAsset.value
                    val allocation = if (totalValue > 0.0) {
                        asset.value / totalValue * 100.0
                    } else {
                        0.0
                    }
                    val targetAllocation = if (asset.includeInTarget && targetPortfolioValue > 0.0) {
                        asset.value / targetPortfolioValue * 100.0
                    } else {
                        0.0
                    }

                    addAssetCard(
                        parent = parent,
                        asset = asset,
                        allocation = allocation,
                        targetAllocation = targetAllocation,
                        targetPortfolioValue = targetPortfolioValue,
                        tolerancePercent = tolerance,
                        index = indexedAsset.index
                    )
                }
        }
    }

    private fun showToolsDialog() {
        val options = arrayOf(
            "AI Portfolio Summary",
            "Google Drive / Cloud Backup",
            "Edit Targets",
            "Rebalance Tolerance",
            "Portfolio History",
            "Manage Categories",
            "App Lock",
            "Settings",
            "Backup / Restore",
            "Reset Portfolio"
        )

        AlertDialog.Builder(this)
            .setTitle(ui("Portfolio Tools"))
            .setItems(options.map(::ui).toTypedArray()) { _, which ->
                when (which) {
                    0 -> sharePrivacySafeAiSummary()
                    1 -> showCloudBackupDialog()
                    2 -> showTargetsDialog()
                    3 -> showToleranceDialog()
                    4 -> showHistoryDialog()
                    5 -> showCategoryManagerDialog()
                    6 -> showAppLockDialog()
                    7 -> showSettingsDialog()
                    8 -> showBackupDialog()
                    9 -> showResetDemoDialog()
                }
            }
            .setNegativeButton(ui("Close"), null)
            .show()
    }

    private fun showResetDemoDialog() {
        AlertDialog.Builder(this)
            .setTitle(ui("Reset portfolio?"))
            .setMessage(ui("This will delete all assets and transaction history. You can undo it afterward."))
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Reset")) { _, _ ->
                pushUndoCheckpoint()
                saveAssets(emptyList())
                saveTransactions(emptyList())
                saveSnapshots(emptyList())
                showPortfolioScreen()
            }
            .show()
    }

    private fun addPeriodSummary(parent: LinearLayout, period: String) {
        val transactions = loadTransactions()
            .filter { it.timestamp >= periodStartMillis(period) }

        val buyTotal = transactions
            .filter { it.type == "BUY" }
            .sumOf { it.quantity * it.price }

        val sellTotal = transactions
            .filter { it.type == "SELL" }
            .sumOf { it.quantity * it.price }

        val realizedProfit = transactions
            .filter { it.type == "SELL" }
            .sumOf { it.realizedProfit }

        val income = transactions
            .filter { it.type == "INCOME" }
            .sumOf { it.price }

        val expense = transactions
            .filter { it.type == "EXPENSE" }
            .sumOf { it.price }

        parent.addView(
            TextView(this).apply {
                text = DashboardPresentation.summaryHeading(period, uiLanguage())
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
                setPadding(0, dp(12), 0, dp(6))
            }
        )

        parent.addView(
            TextView(this).apply {
                text = buildString {
                    append(ui("Buy: "))
                    append(formatToman(buyTotal))
                    append(ui("  •  Sell: "))
                    append(formatToman(sellTotal))
                    append(ui("\nRealized P/L: "))
                    append(formatSignedToman(realizedProfit))
                    append(ui("\nIncome: "))
                    append(formatToman(income))
                    append(ui("  •  Expense: "))
                    append(formatToman(expense))
                }
                textSize = 13f
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = GradientDrawable().apply {
                    setColor(PortfolioAppearance.SURFACE)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), PortfolioAppearance.BORDER)
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )
    }

    private fun showCategoryManagerDialog() {
        val categories = loadCategories()
        val options = categories + "+ Add Category"

        AlertDialog.Builder(this)
            .setTitle(ui("Categories"))
            .setItems(options.toTypedArray()) { _, which ->
                if (which == categories.size) {
                    showAddCategoryDialog()
                } else {
                    val category = categories[which]
                    if (coreCategories.contains(category)) {
                        AlertDialog.Builder(this)
                            .setTitle(category)
                            .setMessage(ui("This is a core category used by portfolio logic. Add a custom category if you need a different label."))
                            .setPositiveButton(ui("OK"), null)
                            .show()
                    } else {
                        showCustomCategoryActions(category)
                    }
                }
            }
            .setNegativeButton(ui("Close"), null)
            .show()
    }

    private fun showAddCategoryDialog() {
        val input = EditText(this).apply {
            hint = ui("Category name")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(ui("Add Category"))
            .setView(input)
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Add"), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                val categories = loadCategories()

                when {
                    name.isBlank() -> input.error = ui("Enter a category name")
                    categories.any { it.equals(name, ignoreCase = true) } ->
                        input.error = ui("Category already exists")
                    else -> {
                        pushUndoCheckpoint()
                        categories.add(name)
                        saveCategories(categories)
                        dialog.dismiss()
                        showCategoryManagerDialog()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showCustomCategoryActions(category: String) {
        AlertDialog.Builder(this)
            .setTitle(category)
            .setItems(arrayOf(ui("Rename"), ui("Delete"))) { _, which ->
                if (which == 0) {
                    showRenameCategoryDialog(category)
                } else {
                    confirmDeleteCategory(category)
                }
            }
            .setNegativeButton(ui("Cancel"), null)
            .show()
    }

    private fun showRenameCategoryDialog(oldName: String) {
        val input = EditText(this).apply {
            hint = ui("Category name")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(oldName)
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(ui("Rename Category"))
            .setView(input)
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Save"), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text.toString().trim()
                val categories = loadCategories()

                when {
                    newName.isBlank() -> input.error = ui("Enter a category name")
                    categories.any {
                        !it.equals(oldName, ignoreCase = true) &&
                            it.equals(newName, ignoreCase = true)
                    } -> input.error = ui("Category already exists")
                    newName == oldName -> dialog.dismiss()
                    else -> {
                        val assets = loadAssets()
                        pushUndoCheckpoint()

                        val categoryIndex = categories.indexOf(oldName)
                        if (categoryIndex >= 0) {
                            categories[categoryIndex] = newName
                        }

                        assets.indices.forEach { index ->
                            if (assets[index].category == oldName) {
                                assets[index] = assets[index].copy(category = newName)
                            }
                        }

                        saveCategories(categories)
                        saveAssets(assets)
                        dialog.dismiss()
                        showPortfolioScreen()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun confirmDeleteCategory(category: String) {
        val usedCount = loadAssets().count { it.category == category }
        val message = if (usedCount > 0) {
            "$usedCount asset(s) use this category. They will be moved to Other."
        } else {
            "Delete this custom category?"
        }

        AlertDialog.Builder(this)
            .setTitle(ui("Delete ") + category + "?")
            .setMessage(message)
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Delete")) { _, _ ->
                val categories = loadCategories()
                val assets = loadAssets()

                pushUndoCheckpoint()

                categories.removeAll { it.equals(category, ignoreCase = true) }
                assets.indices.forEach { index ->
                    if (assets[index].category == category) {
                        assets[index] = assets[index].copy(category = "Other")
                    }
                }

                saveCategories(categories)
                saveAssets(assets)
                showPortfolioScreen()
            }
            .show()
    }

    private fun showSettingsDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        fun addLabel(textValue: String) {
            form.addView(
                TextView(this).apply {
                    text = ui(textValue)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                    setPadding(0, dp(10), 0, dp(4))
                }
            )
        }

        addLabel("Language / زبان")
        val languageSpinner = Spinner(this)
        languageSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, listOf("English", "فارسی")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        languageSpinner.setSelection(if (uiLanguage() == "fa") 1 else 0)
        form.addView(languageSpinner)

        addLabel("Display unit")
        val unitSpinner = Spinner(this)
        val unitAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            displayUnits.map(::ui)
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        unitSpinner.adapter = unitAdapter
        unitSpinner.setSelection(
            displayUnits.indexOf(loadDisplayUnit()).let { if (it >= 0) it else 0 }
        )
        form.addView(unitSpinner)

        addLabel("Summary period")
        val periodSpinner = Spinner(this)
        val periodAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            summaryPeriods.map(::ui)
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        periodSpinner.adapter = periodAdapter
        periodSpinner.setSelection(
            summaryPeriods.indexOf(loadSummaryPeriod()).let { if (it >= 0) it else 2 }
        )
        form.addView(periodSpinner)

        addLabel("Automatic Nobitex refresh while app is open")
        val refreshSpinner = Spinner(this)
        val refreshAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            autoRefreshLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        refreshSpinner.adapter = refreshAdapter
        val currentRefresh = loadAutoRefreshMinutes()
        refreshSpinner.setSelection(
            autoRefreshValues.indexOf(currentRefresh).let { if (it >= 0) it else 0 }
        )
        form.addView(refreshSpinner)

        val dialog = AlertDialog.Builder(this)
            .setTitle(ui("Settings"))
            .setView(form)
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Save"), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val unit = displayUnits[unitSpinner.selectedItemPosition]
                val period = summaryPeriods[periodSpinner.selectedItemPosition]
                val refresh = autoRefreshValues[refreshSpinner.selectedItemPosition]
                val language = if (languageSpinner.selectedItemPosition == 1) "fa" else "en"

                saveSettings(unit, period, refresh)
                val languageChanged = language != uiLanguage()
                getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                    .putString(uiLanguageKey, language).apply()
                scheduleAutoRefresh()
                dialog.dismiss()
                if (languageChanged) recreate() else showPortfolioScreen()
            }
        }

        dialog.show()
    }

    private fun showTargetsDialog() {
        val assets = loadAssets()
        val targetEntries = assets.withIndex().filter { it.value.includeInTarget }
        if (targetEntries.isEmpty()) {
            Toast.makeText(this, ui("No assets are included in target allocation."), Toast.LENGTH_SHORT).show()
            return
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        val inputs = mutableListOf<Pair<Int, EditText>>()

        targetEntries.forEach { indexed ->
            val index = indexed.index
            val asset = indexed.value
            form.addView(
                TextView(this).apply {
                    text = asset.name
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                    setPadding(0, dp(8), 0, 0)
                }
            )

            val input = EditText(this).apply {
                hint = ui("Target %")
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(formatQuantity(asset.targetPercent))
            }
            inputs.add(index to input)
            form.addView(input)
        }

        val scroll = ScrollView(this).apply {
            addView(form)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(ui("Edit Target Allocation"))
            .setMessage(ui("Targets must add up to 100%."))
            .setView(scroll)
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Save"), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updatedTargets = mutableMapOf<Int, Double>()
                var invalid = false

                inputs.forEach { (index, input) ->
                    val value = UiText.parseUserNumber(input.text.toString().trim().replace(",", ""))
                    if (value == null || !value.isFinite() || value < 0.0 || value > 100.0) {
                        input.error = ui("Enter 0 to 100")
                        invalid = true
                    } else {
                        updatedTargets[index] = value
                    }
                }

                if (invalid) {
                    return@setOnClickListener
                }

                val total = updatedTargets.values.sum()
                if (kotlin.math.abs(total - 100.0) > 0.01) {
                    Toast.makeText(
                        this,
                        String.format(Locale.US, "Target total is %.1f%%. It must be 100%%.", total),
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                val updatedAssets = loadAssets()
                pushUndoCheckpoint()
                updatedTargets.forEach { (index, target) ->
                    if (index in updatedAssets.indices) {
                        updatedAssets[index] = updatedAssets[index].copy(targetPercent = target)
                    }
                }

                saveAssets(updatedAssets)
                dialog.dismiss()
                showPortfolioScreen()
            }
        }

        dialog.show()
    }

    private fun showToleranceDialog() {
        val input = EditText(this).apply {
            hint = ui("Tolerance (%)")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatQuantity(loadTolerance()))
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(ui("Rebalance Tolerance"))
            .setMessage(ui("Assets within this distance from target are treated as on target."))
            .setView(input)
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Save"), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = UiText.parseUserNumber(input.text.toString().trim().replace(",", ""))

                if (value == null || !value.isFinite() || value < 0.0 || value > 20.0) {
                    input.error = ui("Enter a value from 0 to 20")
                    return@setOnClickListener
                }

                pushUndoCheckpoint()
                saveTolerance(value)
                dialog.dismiss()
                showPortfolioScreen()
            }
        }

        dialog.show()
    }

    private fun addRebalanceSummary(
        parent: LinearLayout,
        assets: List<Asset>,
        totalPortfolioValue: Double,
        totalTarget: Double,
        tolerancePercent: Double
    ) {
        parent.addView(
            TextView(this).apply {
                text = ui("Rebalance Summary")
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
                setPadding(0, dp(14), 0, dp(6))
            }
        )

        if (kotlin.math.abs(totalTarget - 100.0) > 0.01) {
            parent.addView(
                TextView(this).apply {
                    text = ui("Set targets to a total of 100% to activate rebalance guidance.")
                    textSize = 14f
                    setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                    setPadding(0, 0, 0, dp(10))
                }
            )
            return
        }

        val actions = assets.mapNotNull { asset ->
            val currentAllocation = if (totalPortfolioValue > 0.0) {
                asset.value / totalPortfolioValue * 100.0
            } else {
                0.0
            }
            val gap = currentAllocation - asset.targetPercent

            if (kotlin.math.abs(gap) <= tolerancePercent) {
                null
            } else {
                val desiredValue = totalPortfolioValue * asset.targetPercent / 100.0
                val amount = desiredValue - asset.value
                Triple(asset.name, amount, kotlin.math.abs(amount))
            }
        }.sortedByDescending { it.third }

        if (actions.isEmpty()) {
            parent.addView(
                TextView(this).apply {
                    text = ui("Portfolio is within tolerance. No rebalance action is needed.")
                    textSize = 14f
                    setTextColor(PortfolioAppearance.SUCCESS)
                    setPadding(0, 0, 0, dp(10))
                }
            )
            return
        }

        actions.forEach { (name, amount, _) ->
            parent.addView(
                TextView(this).apply {
                    text = if (amount > 0.0) {
                        ui("Buy") + " " + name + " • " + formatToman(amount)
                    } else {
                        ui("Sell") + " " + name + " • " + formatToman(kotlin.math.abs(amount))
                    }
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = GradientDrawable().apply {
                        setColor(PortfolioAppearance.SURFACE)
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), PortfolioAppearance.BORDER)
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
            )
        }
    }

    private fun showAssetDialog(index: Int? = null, existing: Asset? = null) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val nameInput = EditText(this).apply {
            hint = ui("Asset name")
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.name ?: "")
        }

        val availableCategories = loadCategories()
        val categorySpinner = Spinner(this)
        val categoryAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            availableCategories.map(::ui)
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        categorySpinner.adapter = categoryAdapter
        val selectedCategory = existing?.category ?: "Other"
        val categoryIndex = availableCategories.indexOf(selectedCategory)
            .let { if (it >= 0) it else availableCategories.indexOf("Other").coerceAtLeast(0) }
        categorySpinner.setSelection(categoryIndex)

        val quantityInput = EditText(this).apply {
            hint = ui("Quantity")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existing?.let { formatQuantity(it.quantity).replace(",", "") } ?: "")
        }

        val priceInput = EditText(this).apply {
            hint = ui("Current price per unit (Toman)")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existing?.let { it.price.toLong().toString() } ?: "")
        }

        val averageCostInput = EditText(this).apply {
            hint = ui("Average cost per unit (Toman)")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existing?.let { it.averageCost.toLong().toString() } ?: "")
        }

        val priceSourceSpinner = Spinner(this)
        val priceSourceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            priceSources.map(::ui)
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        priceSourceSpinner.adapter = priceSourceAdapter
        val selectedSource = existing?.priceSource ?: "Manual"
        val sourceIndex = priceSources.indexOf(selectedSource).let { if (it >= 0) it else 0 }
        priceSourceSpinner.setSelection(sourceIndex)

        val symbolInput = EditText(this).apply {
            hint = ui("Market symbol (e.g. BTC, ETH, SOL)")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(existing?.symbol ?: "")
        }

        val includeTargetCheck = CheckBox(this).apply {
            text = ui("Include in target allocation")
            isChecked = existing?.includeInTarget ?: true
            setPadding(0, dp(6), 0, 0)
        }

        val targetInput = EditText(this).apply {
            hint = ui("Target allocation (%)")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existing?.let { formatQuantity(it.targetPercent) } ?: "0")
        }

        form.addView(nameInput)
        form.addView(categorySpinner)
        form.addView(quantityInput)
        form.addView(priceInput)
        form.addView(averageCostInput)
        form.addView(priceSourceSpinner)
        form.addView(symbolInput)
        form.addView(includeTargetCheck)
        form.addView(targetInput)

        val isEditing = index != null && existing != null
        val dialog = AlertDialog.Builder(this)
            .setTitle(ui(if (isEditing) "Edit Asset" else "Add Asset"))
            .setView(form)
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui(if (isEditing) "Save" else "Add"), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val category = availableCategories[categorySpinner.selectedItemPosition]
                val quantity = UiText.parseUserNumber(quantityInput.text.toString().trim().replace(",", ""))
                val price = UiText.parseUserNumber(priceInput.text.toString().trim().replace(",", ""))
                val averageCostText = averageCostInput.text.toString().trim().replace(",", "")
                val averageCost = if (averageCostText.isBlank()) price else UiText.parseUserNumber(averageCostText)
                val targetPercent = UiText.parseUserNumber(targetInput.text.toString().trim().replace(",", ""))
                val priceSource = priceSources[priceSourceSpinner.selectedItemPosition]
                val symbol = symbolInput.text.toString().trim().uppercase(Locale.US)

                when {
                    name.isEmpty() -> nameInput.error = ui("Enter an asset name")
                    quantity == null || !quantity.isFinite() || quantity <= 0.0 ->
                        quantityInput.error = ui("Enter a quantity greater than zero")
                    price == null || !price.isFinite() || price < 0.0 ->
                        priceInput.error = ui("Enter a valid current price")
                    averageCost == null || !averageCost.isFinite() || averageCost < 0.0 ->
                        averageCostInput.error = ui("Enter a valid average cost")
                    targetPercent == null || !targetPercent.isFinite() || targetPercent < 0.0 || targetPercent > 100.0 ->
                        targetInput.error = ui("Target must be between 0 and 100")
                    priceSource == "Nobitex" && symbol.isBlank() ->
                        symbolInput.error = ui("Enter a Nobitex market symbol")
                    priceSource == "Nobitex" && category != "Crypto" ->
                        symbolInput.error = ui("Nobitex source is currently for Crypto assets")
                    else -> {
                        val assets = loadAssets()
                        val updated = (existing ?: Asset(
                            name, category, quantity, price, averageCost, targetPercent,
                            includeTargetCheck.isChecked, priceSource, symbol
                        )).copy(
                            name = name,
                            category = category,
                            quantity = quantity,
                            price = price,
                            averageCost = averageCost,
                            targetPercent = targetPercent,
                            includeInTarget = includeTargetCheck.isChecked,
                            priceSource = priceSource,
                            symbol = symbol
                        )

                        if (isEditing && index != null && index in assets.indices) {
                            assets[index] = updated
                        } else {
                            assets.add(updated)
                        }

                        pushUndoCheckpoint()
                        saveAssets(assets)
                        dialog.dismiss()
                        showPortfolioScreen()

                        Toast.makeText(
                            this,
                            if (isEditing) name + " updated" else name + " added",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun addAssetCard(
        parent: LinearLayout,
        asset: Asset,
        allocation: Double,
        targetAllocation: Double,
        targetPortfolioValue: Double,
        tolerancePercent: Double,
        index: Int
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(15))
            background = GradientDrawable().apply {
                setColor(PortfolioAppearance.SURFACE)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), PortfolioAppearance.BORDER)
            }
        }

        card.addView(
            TextView(this).apply {
                text = asset.name
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
                setOnClickListener { showAssetDialog(index, asset) }
            }
        )

        card.addView(
            TextView(this).apply {
                text = buildString {
                    append(ui(asset.category))
                    append(" • ")
                    append(ui(asset.priceSource))
                    if (asset.symbol.isNotBlank()) {
                        append(" • ")
                        append(asset.symbol)
                    }
                }
                textSize = 13f
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                setPadding(0, dp(3), 0, dp(5))
            }
        )

        card.addView(
            TextView(this).apply {
                text = ui("Quantity: ") + formatQuantity(asset.quantity)
                textSize = 14f
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
            }
        )

        card.addView(
            TextView(this).apply {
                text = ui("Price: ") + formatToman(asset.price)
                textSize = 14f
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
            }
        )

        card.addView(
            TextView(this).apply {
                text = ui("Avg. cost: ") + formatToman(asset.averageCost)
                textSize = 14f
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
            }
        )

        card.addView(
            TextView(this).apply {
                text = ui("Value: ") + formatToman(asset.value)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
                setPadding(0, dp(6), 0, dp(2))
            }
        )

        card.addView(
            TextView(this).apply {
                text = ui("P/L: ") + formatSignedToman(asset.profit)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    when {
                        asset.profit > 0.0 -> PortfolioAppearance.SUCCESS
                        asset.profit < 0.0 -> PortfolioAppearance.ERROR
                        else -> PortfolioAppearance.TEXT_SECONDARY
                    }
                )
            }
        )

        card.addView(
            TextView(this).apply {
                text = if (asset.includeInTarget) {
                    String.format(
                        Locale.US,
                        ui("Portfolio: %.1f%%  •  Target pool: %.1f%%  •  Target: %.1f%%"),
                        allocation,
                        targetAllocation,
                        asset.targetPercent
                    )
                } else {
                    String.format(Locale.US, ui("Portfolio: %.1f%%  •  Target: Excluded"), allocation)
                }
                textSize = 14f
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                setPadding(0, dp(2), 0, dp(2))
            }
        )

        val gap = if (asset.includeInTarget) targetAllocation - asset.targetPercent else 0.0
        card.addView(
            TextView(this).apply {
                text = if (asset.includeInTarget) {
                    String.format(Locale.US, ui("Distance to target: %+.1f%%"), gap)
                } else {
                    ui("Distance to target: Not applicable")
                }
                textSize = 13f
                setTextColor(
                    if (!asset.includeInTarget || kotlin.math.abs(gap) <= tolerancePercent) {
                        PortfolioAppearance.SUCCESS
                    } else {
                        PortfolioAppearance.WARNING
                    }
                )
                setPadding(0, 0, 0, dp(3))
            }
        )

        val desiredValue = targetPortfolioValue * asset.targetPercent / 100.0
        val rebalanceAmount = desiredValue - asset.value
        card.addView(
            TextView(this).apply {
                text = when {
                    !asset.includeInTarget -> ui("Rebalance: Excluded from target")
                    asset.targetPercent <= 0.0 -> ui("Rebalance: No target set")
                    kotlin.math.abs(gap) <= tolerancePercent -> ui("Rebalance: On target")
                    rebalanceAmount > 0.0 -> ui("Rebalance: Buy about ") + formatToman(rebalanceAmount)
                    else -> ui("Rebalance: Sell about ") + formatToman(kotlin.math.abs(rebalanceAmount))
                }
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    if ((!asset.includeInTarget || kotlin.math.abs(gap) <= tolerancePercent) && asset.targetPercent > 0.0) {
                        PortfolioAppearance.SUCCESS
                    } else {
                        PortfolioAppearance.TEXT_SECONDARY
                    }
                )
                setPadding(0, 0, 0, dp(8))
            }
        )

        val transactionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val buyButton = Button(this).apply {
            text = ui("Buy")
            isAllCaps = false
            setOnClickListener { showTransactionDialog(index, asset, true) }
        }

        val sellButton = Button(this).apply {
            text = ui("Sell")
            isAllCaps = false
            setOnClickListener { showTransactionDialog(index, asset, false) }
        }

        transactionRow.addView(
            buyButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(5)
            }
        )
        transactionRow.addView(
            sellButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(5)
            }
        )

        if (asset.category == "Cash") {
            val balanceButton = Button(this).apply {
                text = ui("Set Final Balance")
                isAllCaps = false
                setOnClickListener { showCashBalanceDialog(index, asset) }
            }
            card.addView(
                balanceButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        } else {
            card.addView(transactionRow)
        }

        card.addView(
            TextView(this).apply {
                text = ui("Tap name to edit • Long press card to delete")
                textSize = 12f
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                setPadding(0, dp(7), 0, 0)
            }
        )

        card.setOnLongClickListener {
            confirmDeleteAsset(index, asset)
            true
        }

        parent.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )
    }

    private fun showTransactionDialog(index: Int, asset: Asset, isBuy: Boolean) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val quantityInput = EditText(this).apply {
            hint = ui("Quantity")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val priceInput = EditText(this).apply {
            hint = ui("Transaction price per unit (Toman)")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(asset.price.toLong().toString())
        }

        form.addView(quantityInput)
        form.addView(priceInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle((if (isBuy) "Buy " else "Sell ") + asset.name)
            .setView(form)
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(if (isBuy) "Buy" else "Sell", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val quantity = UiText.parseUserNumber(quantityInput.text.toString().trim().replace(",", ""))
                val transactionPrice = UiText.parseUserNumber(priceInput.text.toString().trim().replace(",", ""))

                when {
                    quantity == null || !quantity.isFinite() || quantity <= 0.0 ->
                        quantityInput.error = ui("Enter a quantity greater than zero")
                    transactionPrice == null || !transactionPrice.isFinite() || transactionPrice < 0.0 ->
                        priceInput.error = ui("Enter a valid transaction price")
                    !isBuy && quantity > asset.quantity ->
                        quantityInput.error = ui("You only own ") + formatQuantity(asset.quantity)
                    else -> {
                        val assets = loadAssets()
                        if (index !in assets.indices) {
                            dialog.dismiss()
                            showPortfolioScreen()
                            return@setOnClickListener
                        }

                        val current = assets[index]
                        val transactions = loadTransactions()

                        if (isBuy) {
                            val newQuantity = current.quantity + quantity
                            val newAverageCost =
                                ((current.quantity * current.averageCost) + (quantity * transactionPrice)) /
                                    newQuantity

                            val updatedAsset = current.copy(
                                quantity = newQuantity,
                                price = transactionPrice,
                                averageCost = newAverageCost
                            )
                            assets[index] = updatedAsset

                            transactions.add(
                                Transaction(
                                    id = UUID.randomUUID().toString(),
                                    type = "BUY",
                                    assetName = current.name,
                                    quantity = quantity,
                                    price = transactionPrice,
                                    realizedProfit = 0.0,
                                    timestamp = System.currentTimeMillis(),
                                    beforeAssetJson = assetToJson(current),
                                    afterAssetJson = assetToJson(updatedAsset),
                                    managed = true
                                )
                            )
                        } else {
                            val realizedProfit = (transactionPrice - current.averageCost) * quantity
                            val newQuantity = current.quantity - quantity

                            val updatedAsset = if (newQuantity <= 0.0000001) {
                                assets.removeAt(index)
                                null
                            } else {
                                current.copy(
                                    quantity = newQuantity,
                                    price = transactionPrice
                                ).also { assets[index] = it }
                            }

                            transactions.add(
                                Transaction(
                                    id = UUID.randomUUID().toString(),
                                    type = "SELL",
                                    assetName = current.name,
                                    quantity = quantity,
                                    price = transactionPrice,
                                    realizedProfit = realizedProfit,
                                    timestamp = System.currentTimeMillis(),
                                    beforeAssetJson = assetToJson(current),
                                    afterAssetJson = updatedAsset?.let { assetToJson(it) },
                                    managed = true
                                )
                            )
                        }

                        pushUndoCheckpoint()
                        saveAssets(assets)
                        saveTransactions(transactions)
                        recordSnapshot(assets)
                        dialog.dismiss()
                        showPortfolioScreen()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showPriceCenterScreen() {
        onPortfolioScreen = false
        onPriceCenterScreen = true
        val assets = loadAssets()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(PortfolioAppearance.BACKGROUND)
        }

        container.addView(
            TextView(this).apply {
                text = ui("Price Center")
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
            }
        )

        container.addView(
            TextView(this).apply {
                text = ui("Update all current prices in one place.")
                textSize = 14f
                setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                setPadding(0, dp(6), 0, dp(16))
            }
        )

        val inputs = mutableListOf<Pair<Int, EditText>>()

        assets.forEachIndexed { index, asset ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(PortfolioAppearance.SURFACE)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), PortfolioAppearance.BORDER)
                }
            }

            row.addView(
                TextView(this).apply {
                    text = buildString {
                        append(asset.name)
                        append(" • ")
                        append(ui(asset.category))
                        append(" • ")
                        append(ui(asset.priceSource))
                        if (asset.symbol.isNotBlank()) {
                            append(" • ")
                            append(asset.symbol)
                        }
                    }
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                }
            )

            val input = EditText(this).apply {
                hint = ui("Current price (Toman)")
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(asset.price.toLong().toString())
            }

            inputs.add(index to input)
            row.addView(input)

            container.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            )
        }

        val saveButton = Button(this).apply {
            text = ui("Save All Prices")
            isAllCaps = false
            setOnClickListener {
                val updatedAssets = loadAssets()
                var invalid = false

                inputs.forEach { (index, input) ->
                    val value = UiText.parseUserNumber(input.text.toString().trim().replace(",", ""))
                    if (value == null || !value.isFinite() || value < 0.0) {
                        input.error = ui("Enter a valid price")
                        invalid = true
                    } else if (index in updatedAssets.indices) {
                        updatedAssets[index] = updatedAssets[index].copy(price = value)
                    }
                }

                if (!invalid) {
                    pushUndoCheckpoint()
                    saveAssets(updatedAssets)
                    recordSnapshot(updatedAssets)
                    markPriceUpdate()
                    Toast.makeText(this@MainActivity, ui("All prices updated."), Toast.LENGTH_SHORT).show()
                    showPortfolioScreen()
                }
            }
        }

        val apiButton = Button(this).apply {
            text = ui("Update Nobitex Prices")
            isAllCaps = false
            setOnClickListener { updateNobitexPrices() }
        }

        val backButton = Button(this).apply {
            text = ui("Back to Portfolio")
            isAllCaps = false
            setOnClickListener { showPortfolioScreen() }
        }

        container.addView(saveButton)
        container.addView(
            apiButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )
        container.addView(
            backButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )

        showContentRespectingSystemBars(
            ScrollView(this).apply {
                setBackgroundColor(PortfolioAppearance.BACKGROUND)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                addView(container)
            }
        )
    }

    private fun httpGet(urlText: String): String {
        val connection = URL(urlText).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "InvestmentAndroid/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP " + code)
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun updateNobitexPrices(showResult: Boolean = true) {
        val currentAssets = loadAssets()
        val apiEntries = currentAssets.withIndex().filter {
            it.value.priceSource == "Nobitex" &&
                it.value.category == "Crypto" &&
                it.value.symbol.isNotBlank()
        }

        if (apiEntries.isEmpty()) {
            if (showResult) {
                AlertDialog.Builder(this)
                    .setTitle(ui("Nobitex"))
                    .setMessage(ui("No Crypto assets are configured with Nobitex as their price source."))
                    .setPositiveButton(ui("OK"), null)
                    .show()
            }
            return
        }

        if (cloudOperationInProgress.get() || !priceUpdateInProgress.compareAndSet(false, true)) {
            if (showResult) {
                Toast.makeText(
                    this,
                    ui("A price, backup, or cloud operation is already running."),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        if (showResult) {
            Toast.makeText(this, ui("Updating Nobitex prices..."), Toast.LENGTH_SHORT).show()
        }

        Thread {
            try {
                val symbols = apiEntries
                    .map { it.value.symbol.lowercase(Locale.US) }
                    .filter { it != "usdt" }
                    .distinct()

                val usdtResponse = httpGet(
                    "https://api.nobitex.ir/market/stats?srcCurrency=usdt&dstCurrency=rls"
                )
                val usdtRoot = JSONObject(usdtResponse)
                if (usdtRoot.optString("status") != "ok") {
                    throw IllegalStateException("Nobitex USDT market returned an error.")
                }

                val usdtRls = usdtRoot
                    .getJSONObject("stats")
                    .getJSONObject("usdt-rls")
                    .getString("latest")
                    .toDouble()

                if (!usdtRls.isFinite() || usdtRls <= 0.0) {
                    throw IllegalStateException("Nobitex returned an invalid USDT price.")
                }

                val usdtToman = usdtRls / 10.0

                val cryptoStats = if (symbols.isNotEmpty()) {
                    val joined = symbols.joinToString(",")
                    val response = httpGet(
                        "https://api.nobitex.ir/market/stats?srcCurrency=" +
                            joined +
                            "&dstCurrency=usdt"
                    )
                    val root = JSONObject(response)
                    if (root.optString("status") != "ok") {
                        throw IllegalStateException("Nobitex crypto market returned an error.")
                    }
                    root.getJSONObject("stats")
                } else {
                    JSONObject()
                }

                val updatedAssets = currentAssets.toMutableList()
                val updatedNames = mutableListOf<String>()
                val failedNames = mutableListOf<String>()

                apiEntries.forEach { indexed ->
                    val index = indexed.index
                    val asset = indexed.value
                    val symbol = asset.symbol.lowercase(Locale.US)

                    try {
                        val priceToman = if (symbol == "usdt") {
                            usdtToman
                        } else {
                            val key = symbol + "-usdt"
                            val usdtPrice = cryptoStats
                                .getJSONObject(key)
                                .getString("latest")
                                .toDouble()
                            usdtPrice * usdtToman
                        }

                        if (index in updatedAssets.indices && priceToman.isFinite() && priceToman > 0.0) {
                            updatedAssets[index] = updatedAssets[index].copy(price = priceToman)
                            updatedNames.add(asset.name)
                        } else {
                            failedNames.add(asset.name)
                        }
                    } catch (_: Exception) {
                        failedNames.add(asset.name)
                    }
                }

                if (updatedNames.isEmpty()) {
                    throw IllegalStateException("No configured Nobitex prices could be updated.")
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        priceUpdateInProgress.set(false)
                        return@runOnUiThread
                    }
                    val latestAssets = loadAssets()
                    if (latestAssets.size != currentAssets.size ||
                        latestAssets.indices.any { !assetsEquivalent(latestAssets[it], currentAssets[it]) }
                    ) {
                        priceUpdateInProgress.set(false)
                        if (showResult) {
                            AlertDialog.Builder(this)
                                .setTitle(ui("Prices Not Applied"))
                                .setMessage(ui("The portfolio changed while prices were downloading. Try again; no newer edits were overwritten."))
                                .setPositiveButton(ui("OK"), null)
                                .show()
                        }
                        return@runOnUiThread
                    }
                    pushUndoCheckpoint()
                    saveAssets(updatedAssets)
                    recordSnapshot(updatedAssets)
                    markPriceUpdate()
                    priceUpdateInProgress.set(false)
                    if (showResult) {
                        val message = buildString {
                            append("Updated: ")
                            append(updatedNames.joinToString(", "))
                            append("\nUSDT rate: ")
                            append(formatToman(usdtToman))
                            if (failedNames.isNotEmpty()) {
                                append("\nFailed: ")
                                append(failedNames.joinToString(", "))
                            }
                        }

                        AlertDialog.Builder(this)
                            .setTitle(ui("Nobitex Update"))
                            .setMessage(message)
                            .setPositiveButton(ui("OK")) { _, _ -> showPortfolioScreen() }
                            .show()
                    } else if (onPortfolioScreen) {
                        showPortfolioScreen()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    priceUpdateInProgress.set(false)
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (showResult) {
                        AlertDialog.Builder(this)
                            .setTitle(ui("Nobitex Update Failed"))
                            .setMessage(ui(error.message ?: "Could not update market prices."))
                            .setPositiveButton(ui("OK"), null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun showCashBalanceDialog(index: Int, asset: Asset) {
        val input = EditText(this).apply {
            hint = ui("Final balance (Toman)")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(asset.value.toLong().toString())
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(ui("Set ") + asset.name + " Balance")
            .setMessage(ui("The app will infer the difference as income or expense."))
            .setView(input)
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Save"), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val finalBalance = UiText.parseUserNumber(input.text.toString().trim().replace(",", ""))
                if (finalBalance == null || !finalBalance.isFinite() || finalBalance < 0.0) {
                    input.error = ui("Enter a valid balance")
                    return@setOnClickListener
                }

                val assets = loadAssets()
                if (index !in assets.indices) {
                    dialog.dismiss()
                    showPortfolioScreen()
                    return@setOnClickListener
                }

                val current = assets[index]
                val oldBalance = current.value
                val difference = finalBalance - oldBalance

                assets[index] = current.copy(
                    quantity = 1.0,
                    price = finalBalance,
                    averageCost = finalBalance
                )

                val transactions = loadTransactions()
                if (kotlin.math.abs(difference) > 0.01) {
                    val updatedAsset = assets[index]
                    transactions.add(
                        Transaction(
                            id = UUID.randomUUID().toString(),
                            type = if (difference > 0.0) "INCOME" else "EXPENSE",
                            assetName = current.name,
                            quantity = 1.0,
                            price = kotlin.math.abs(difference),
                            realizedProfit = 0.0,
                            timestamp = System.currentTimeMillis(),
                            beforeAssetJson = assetToJson(current),
                            afterAssetJson = assetToJson(updatedAsset),
                            managed = true
                        )
                    )
                }

                pushUndoCheckpoint()
                saveAssets(assets)
                saveTransactions(transactions)
                recordSnapshot(assets)
                dialog.dismiss()
                showPortfolioScreen()
            }
        }

        dialog.show()
    }

    private fun transactionDetails(transaction: Transaction): String {
        return buildString {
            append(ui(transaction.type))
            append(" • ")
            append(transaction.assetName)
            if (transaction.type == "INCOME" || transaction.type == "EXPENSE") {
                append(ui("\nAmount: "))
                append(formatToman(transaction.price))
            } else {
                append(ui("\nQuantity: "))
                append(formatQuantity(transaction.quantity))
                append(ui("\nPrice: "))
                append(formatToman(transaction.price))
            }
            if (transaction.type == "SELL") {
                append(ui("\nRealized P/L: "))
                append(formatSignedToman(transaction.realizedProfit))
            }
            append("\n")
            append(formatDate(transaction.timestamp))
            if (!transaction.managed) {
                append(ui("\nLegacy activity: portfolio-safe revert unavailable"))
            }
        }
    }

    private fun isLatestManagedTransactionForAsset(
        transaction: Transaction,
        allTransactions: List<Transaction>
    ): Boolean {
        return allTransactions
            .filter { it.managed && it.assetName == transaction.assetName }
            .maxByOrNull { it.timestamp }
            ?.id == transaction.id
    }

    private fun canSafelyRevertTransaction(
        transaction: Transaction,
        allTransactions: List<Transaction>,
        assets: List<Asset>
    ): Boolean {
        if (!transaction.managed || !isLatestManagedTransactionForAsset(transaction, allTransactions)) {
            return false
        }

        val expectedAfter = assetFromJson(transaction.afterAssetJson)
        val current = assets.firstOrNull { it.name == transaction.assetName }

        return if (expectedAfter == null) {
            current == null
        } else {
            current != null && assetsEquivalent(current, expectedAfter)
        }
    }

    private fun revertTransaction(transactionId: String) {
        val transactions = loadTransactions()
        val transaction = transactions.firstOrNull { it.id == transactionId } ?: return
        val assets = loadAssets()

        if (!canSafelyRevertTransaction(transaction, transactions, assets)) {
            AlertDialog.Builder(this)
                .setTitle(ui("Cannot Safely Revert"))
                .setMessage(
                    ui("This transaction is not the latest managed change for the asset, ") +
                        "or the asset has changed since it was recorded."
                )
                .setPositiveButton(ui("OK"), null)
                .show()
            return
        }

        val before = assetFromJson(transaction.beforeAssetJson)
        val currentIndex = assets.indexOfFirst { it.name == transaction.assetName }

        pushUndoCheckpoint()

        if (currentIndex >= 0) {
            assets.removeAt(currentIndex)
        }

        if (before != null) {
            assets.add(before)
        }

        transactions.removeAll { it.id == transactionId }
        saveAssets(assets)
        saveTransactions(transactions)
        recordSnapshot(assets)
        showPortfolioScreen()
        Toast.makeText(this, ui("Transaction reverted."), Toast.LENGTH_SHORT).show()
    }

    private fun showActivityDialog(page: Int = 0) {
        val transactions = loadTransactions()
        val ordered = transactions.sortedByDescending { it.timestamp }
        val pageSize = 100
        val pageCount = ((ordered.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val currentPage = page.coerceIn(0, pageCount - 1)
        val assets = loadAssets()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        if (transactions.isEmpty()) {
            content.addView(
                TextView(this).apply {
                    text = ui("No activity yet.")
                    textSize = 14f
                    setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                }
            )
        } else {
            ordered
                .drop(currentPage * pageSize)
                .take(pageSize)
                .forEach { transaction ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = GradientDrawable().apply {
                            setColor(PortfolioAppearance.SURFACE)
                            cornerRadius = dp(10).toFloat()
                            setStroke(dp(1), PortfolioAppearance.BORDER)
                        }
                    }

                    card.addView(
                        TextView(this).apply {
                            text = transactionDetails(transaction)
                            textSize = 13f
                            setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                        }
                    )

                    val canRevert = canSafelyRevertTransaction(transaction, transactions, assets)
                    val revertButton = Button(this).apply {
                        text = ui(if (canRevert) "Revert Transaction" else "Revert Unavailable")
                        isAllCaps = false
                        isEnabled = canRevert
                        setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(ui("Revert transaction?"))
                                .setMessage(
                                    ui("This will reverse the portfolio effect of this transaction ") +
                                        "and remove it from Activity."
                                )
                                .setNegativeButton(ui("Cancel"), null)
                                .setPositiveButton(ui("Revert")) { _, _ ->
                                    revertTransaction(transaction.id)
                                }
                                .show()
                        }
                    }

                    card.addView(
                        revertButton,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp(8)
                        }
                    )

                    content.addView(
                        card,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dp(8)
                        }
                    )
                }
            if (pageCount > 1) {
                content.addView(TextView(this).apply {
                    text = String.format(
                        Locale.US, ui("Page %d of %d • %d saved transactions"),
                        currentPage + 1, pageCount, ordered.size
                    )
                    gravity = Gravity.CENTER
                })
                val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                lateinit var activityDialog: AlertDialog
                for ((label, nextPage) in listOf(
                    ui("Newer") to currentPage - 1,
                    ui("Older") to currentPage + 1
                )) {
                    navigation.addView(Button(this).apply {
                        text = label
                        isAllCaps = false
                        isEnabled = nextPage in 0 until pageCount
                        setOnClickListener {
                            activityDialog.dismiss()
                            showActivityDialog(nextPage)
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                content.addView(navigation)
                val dialog = AlertDialog.Builder(this)
                    .setTitle(ui("Activity Manager"))
                    .setView(ScrollView(this).apply { addView(content) })
                    .setPositiveButton(ui("Close"), null)
                    .create()
                activityDialog = dialog
                dialog.show()
                return
            }
        }

        AlertDialog.Builder(this)
            .setTitle(ui("Activity Manager"))
            .setView(
                ScrollView(this).apply {
                    addView(content)
                }
            )
            .setPositiveButton(ui("Close"), null)
            .show()
    }

    private fun showHistoryDialog() {
        val snapshots = loadSnapshots().takeLast(30).reversed()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        if (snapshots.isEmpty()) {
            content.addView(
                TextView(this).apply {
                    text = ui("No portfolio snapshots yet. Save prices in Price Center to create one.")
                    textSize = 14f
                    setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                }
            )
        } else {
            snapshots.forEachIndexed { index, snapshot ->
                val previous = snapshots.getOrNull(index + 1)
                val change = previous?.let { snapshot.totalValue - it.totalValue }

                content.addView(
                    TextView(this).apply {
                        text = buildString {
                            append(formatToman(snapshot.totalValue))
                            if (change != null) {
                                append(ui("\nChange: "))
                                append(formatSignedToman(change))
                            }
                            append("\n")
                            append(formatDate(snapshot.timestamp))
                        }
                        textSize = 13f
                        setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = GradientDrawable().apply {
                            setColor(PortfolioAppearance.SURFACE)
                            cornerRadius = dp(10).toFloat()
                            setStroke(dp(1), PortfolioAppearance.BORDER)
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(8)
                    }
                )
            }
        }

        AlertDialog.Builder(this)
            .setTitle(ui("Portfolio History"))
            .setView(
                ScrollView(this).apply {
                    addView(content)
                }
            )
            .setPositiveButton(ui("Close"), null)
            .show()
    }

    private fun loadCloudBackupUri(): android.net.Uri? {
        val raw = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(cloudBackupUriKey, null)
            ?: return null

        return try {
            android.net.Uri.parse(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCloudBackupUri(uri: android.net.Uri) {
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(cloudBackupUriKey, uri.toString())
            .apply()
    }

    private fun markCloudSync() {
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putLong(cloudLastSyncKey, System.currentTimeMillis())
            .apply()
    }

    private fun isCloudAutoSyncEnabled(): Boolean =
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .getBoolean(cloudAutoSyncKey, true)

    private fun loadCloudAutoSyncMinutes(): Int =
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .getInt(cloudAutoSyncMinutesKey, 15)
            .let { if (it in listOf(5, 15, 30, 60)) it else 15 }

    private fun setCloudAutoSyncEnabled(enabled: Boolean) {
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putBoolean(cloudAutoSyncKey, enabled)
            .apply()
        scheduleSmartCloudSync()
    }

    private fun setCloudAutoSyncMinutes(minutes: Int) {
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putInt(cloudAutoSyncMinutesKey, minutes)
            .apply()
        scheduleSmartCloudSync()
    }

    private fun stopSmartCloudSync() {
        cloudSyncRunnable?.let { cloudSyncHandler.removeCallbacks(it) }
        cloudSyncRunnable = null
    }

    private fun scheduleSmartCloudSync() {
        stopSmartCloudSync()
        if (!isCloudAutoSyncEnabled() || loadCloudBackupUri() == null) {
            return
        }

        val delay = loadCloudAutoSyncMinutes() * 60_000L
        val runnable = object : Runnable {
            override fun run() {
                if (onPortfolioScreen) {
                    smartCloudSyncCheck()
                }
                cloudSyncHandler.postDelayed(this, delay)
            }
        }

        cloudSyncRunnable = runnable
        cloudSyncHandler.postDelayed({
            if (onPortfolioScreen) {
                smartCloudSyncCheck()
            }
        }, 700L)
        cloudSyncHandler.postDelayed(runnable, delay)
    }

    private fun cloudStatusText(): String {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val connected = loadCloudBackupUri() != null
        val lastSync = prefs.getLong(cloudLastSyncKey, 0L)

        return buildString {
            if (connected) {
                append(ui("Cloud file connected."))
                append(ui("\nSmart sync: "))
                append(
                    if (isCloudAutoSyncEnabled()) {
                        ui("On • every ") + loadCloudAutoSyncMinutes() + ui(" min while app is open")
                    } else {
                        ui("Off")
                    }
                )
                if (lastSync > 0L) {
                    append(ui("\nLast sync: "))
                    append(formatDate(lastSync))
                }
            } else {
                append(ui("Cloud is not connected yet."))
                append(ui("\nChoose the existing Investment-shared.json from Google Drive,"))
                append(ui(" or create it there if this is your first device."))
            }
        }
    }

    private fun showCloudBackupDialog() {
        val connected = loadCloudBackupUri() != null
        val options = if (connected) {
            arrayOf(
                "Check Cloud Status",
                "Sync Now",
                "Load from Cloud",
                if (isCloudAutoSyncEnabled()) "Turn Smart Sync Off" else "Turn Smart Sync On",
                "Smart Sync Interval",
                "Choose Different Cloud File",
                "Disconnect Cloud File",
                "Recover Previous Cloud File"
            )
        } else {
            emptyArray()
        }

        if (!connected) {
            showCloudFirstConnectDialog()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(ui("Google Drive / Cloud Backup"))
            .setMessage(cloudStatusText())
            .setItems(options) { _, which ->
                if (connected) {
                    when (which) {
                        0 -> checkCloudStatus()
                        1 -> syncToCloud()
                        2 -> confirmLoadFromCloud()
                        3 -> {
                            val enabled = !isCloudAutoSyncEnabled()
                            setCloudAutoSyncEnabled(enabled)
                            Toast.makeText(
                                this,
                                if (enabled) "Smart sync enabled." else "Smart sync disabled.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        4 -> showCloudSyncIntervalDialog()
                        5 -> connectExistingCloudBackup()
                        6 -> {
                            getSharedPreferences(prefsName, MODE_PRIVATE)
                                .edit()
                                .remove(cloudBackupUriKey)
                                .remove(cloudLastSyncKey)
                                .remove(cloudSharedFingerprintKey)
                                .remove(cloudLastAutoCheckKey)
                                .apply()
                            stopSmartCloudSync()
                            Toast.makeText(this, ui("Cloud backup disconnected."), Toast.LENGTH_SHORT).show()
                        }
                        7 -> recoverPreviousCloudFile()
                    }
                }
            }
            .setNegativeButton(ui("Close"), null)
            .show()
    }

    private fun showCloudFirstConnectDialog() {
        AlertDialog.Builder(this)
            .setTitle(ui("Connect Google Drive"))
            .setMessage(
                ui("Recommended: use the same Investment-shared.json file as Windows.\n\n") +
                    "On the next screen, open the menu (☰), choose Google Drive, then select " +
                    "Investment-shared.json.\n\nIf the file does not exist yet, choose Create New instead."
            )
            .setPositiveButton(ui("Choose Existing File")) { _, _ ->
                connectExistingCloudBackup()
            }
            .setNeutralButton(ui("Create New")) { _, _ ->
                createCloudBackupFile()
            }
            .setNegativeButton(ui("Cancel"), null)
            .show()
    }

    private fun showCloudSyncIntervalDialog() {
        val values = intArrayOf(5, 15, 30, 60)
        val labels = values.map { "$it minutes" }.toTypedArray()
        val current = values.indexOf(loadCloudAutoSyncMinutes()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(ui("Smart Sync Interval"))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                setCloudAutoSyncMinutes(values[which])
                dialog.dismiss()
                Toast.makeText(
                    this,
                    ui("Smart sync set to every ") + values[which] + " minutes.",
                    Toast.LENGTH_SHORT
                ).show()
                if (onPortfolioScreen) {
                    showPortfolioScreen()
                }
            }
            .setNegativeButton(ui("Cancel"), null)
            .show()
    }

    private fun createCloudBackupFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "Investment-shared.json")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, createCloudBackupRequestCode)
    }

    private fun connectExistingCloudBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Some Drive uploads expose JSON as text/plain or application/octet-stream.
            // Do not hide a valid Investment-shared.json because of provider MIME metadata.
            type = "*/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(intent, connectCloudBackupRequestCode)
        } catch (error: Exception) {
            AlertDialog.Builder(this)
                .setTitle(ui("File Picker Unavailable"))
                .setMessage(
                    ui("Android could not open the system file picker. Make sure the Google Drive app ") +
                        "is installed, signed in, and enabled, then try again."
                )
                .setPositiveButton(ui("OK"), null)
                .show()
        }
    }

    private fun takePersistentCloudPermission(uri: android.net.Uri, data: Intent?) {
        val requestedFlags = data?.flags ?: 0
        val persistableFlags = requestedFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        if (persistableFlags != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, persistableFlags)
            } catch (_: SecurityException) {
                // Some document providers grant session access without persistable permission.
            }
        }
    }

    private fun sharedFingerprint(portfolio: JSONObject): String {
        val assets = portfolio.optJSONArray("assets") ?: JSONArray()
        val normalized = mutableListOf<String>()

        for (index in 0 until assets.length()) {
            val item = assets.optJSONObject(index) ?: continue
            val source = item.optJSONObject("source")
            normalized.add(
                listOf(
                    item.optString("id", ""),
                    item.optString("name", ""),
                    item.optString("category", ""),
                    item.optDouble("quantity", 0.0).toString(),
                    item.optDouble("price_toman", 0.0).toString(),
                    item.optDouble("average_cost_toman", 0.0).toString(),
                    item.optDouble("target_percent", 0.0).toString(),
                    item.optBoolean("include_in_target", false).toString(),
                    item.optString("price_source", ""),
                    item.optString("symbol", ""),
                    item.optString("source_platform", ""),
                    source?.optString("kind", "") ?: "",
                    source?.optString("group_id", "") ?: "",
                    source?.optString("asset_id", "") ?: "",
                    source?.optString("bank_id", "") ?: "",
                    source?.optString("group_kind", "") ?: ""
                ).joinToString("|")
            )
        }

        normalized.sort()
        val payload = buildString {
            append(portfolio.optString("currency", "Toman"))
            append("\n")
            append(portfolio.optDouble("rebalance_tolerance_percent", 0.0))
            append("\n")
            normalized.forEach {
                append(it)
                append("\n")
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun saveCloudBaseline(portfolio: JSONObject) {
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(cloudSharedFingerprintKey, sharedFingerprint(portfolio))
            .apply()
    }

    private fun mergedBackupDocument(existingRaw: String?): JSONObject {
        val root = if (existingRaw.isNullOrBlank()) {
            JSONObject()
        } else {
            PortfolioSafety.validateBackup(existingRaw).also {
                require(it.kind == PortfolioSafety.BackupKind.SHARED) {
                    "Cloud file is not a shared portfolio. No data was overwritten."
                }
            }.root
        }

        val localPortfolio = buildSharedPortfolio()
        val previousPortfolio = root.optJSONObject("sharedPortfolio")
        val previousAssets = previousPortfolio?.optJSONArray("assets")
        val previousById = mutableMapOf<String, JSONObject>()
        if (previousAssets != null) {
            for (index in 0 until previousAssets.length()) {
                val item = previousAssets.optJSONObject(index) ?: continue
                previousById[sharedAssetKey(item)] = item
            }
        }
        val localAssets = localPortfolio.getJSONArray("assets")
        val mergedAssets = JSONArray()
        for (index in 0 until localAssets.length()) {
            val localAsset = localAssets.getJSONObject(index)
            val preserved = previousById[sharedAssetKey(localAsset)]
            val merged = if (preserved == null) JSONObject() else JSONObject(preserved.toString())
            for (key in localAsset.keys()) {
                merged.put(key, localAsset.get(key))
            }
            mergedAssets.put(merged)
        }
        val mergedPortfolio = if (previousPortfolio == null) {
            JSONObject()
        } else {
            JSONObject(previousPortfolio.toString())
        }
        mergedPortfolio.put("currency", "Toman")
        mergedPortfolio.put("assets", mergedAssets)
        mergedPortfolio.put("rebalance_tolerance_percent", loadTolerance())

        root.put("format", PortfolioSafety.SHARED_FORMAT)
        root.put("schemaVersion", PortfolioSafety.SHARED_SCHEMA_VERSION)
        root.put("updatedAt", System.currentTimeMillis())
        root.put("sharedPortfolio", mergedPortfolio)
        val localSupplement = buildAndroidBackupPayload()
        val remoteSupplement = root.optJSONObject("androidBackup")
        if (remoteSupplement != null) {
            for ((key, identity) in listOf("transactions" to "id", "snapshots" to "timestamp")) {
                localSupplement.put(
                    key,
                    PortfolioSafety.mergeHistory(
                        localSupplement.optJSONArray(key) ?: JSONArray(),
                        remoteSupplement.optJSONArray(key) ?: JSONArray(),
                        identity
                    )
                )
            }
        }
        root.put("androidBackup", PortfolioSafety.preserveSupplementalFields(localSupplement, remoteSupplement))
        return root
    }

    private fun sharedAssetKey(item: JSONObject): String {
        val id = item.optString("id", "").trim()
        if (id.isNotBlank()) {
            return id
        }
        val symbol = item.optString("symbol", "").trim().uppercase(Locale.US)
        val name = item.optString("name", "").trim().lowercase(Locale.US)
        val category = item.optString("category", "").trim().lowercase(Locale.US)
        return category + ":" + (if (symbol.isNotBlank()) symbol else name)
    }

    private fun sharedChangeCount(left: JSONObject, right: JSONObject): Int {
        fun mapOfAssets(portfolio: JSONObject): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val assets = portfolio.optJSONArray("assets") ?: JSONArray()
            for (index in 0 until assets.length()) {
                val item = assets.optJSONObject(index) ?: continue
                val source = item.optJSONObject("source")
                val normalized = listOf(
                    item.optString("name", ""),
                    item.optString("category", ""),
                    item.optDouble("quantity", 0.0).toString(),
                    item.optDouble("price_toman", 0.0).toString(),
                    item.optDouble("average_cost_toman", 0.0).toString(),
                    item.optDouble("target_percent", 0.0).toString(),
                    item.optBoolean("include_in_target", false).toString(),
                    item.optString("price_source", ""),
                    item.optString("symbol", ""),
                    item.optString("source_platform", ""),
                    source?.optString("kind", "") ?: "",
                    source?.optString("group_id", "") ?: "",
                    source?.optString("asset_id", "") ?: "",
                    source?.optString("bank_id", "") ?: "",
                    source?.optString("group_kind", "") ?: ""
                ).joinToString("|")
                result[sharedAssetKey(item)] = normalized
            }
            return result
        }

        val a = mapOfAssets(left)
        val b = mapOfAssets(right)
        return (a.keys + b.keys).count { key -> a[key] != b[key] }
    }

    private fun sharedPortfolioSummary(portfolio: JSONObject): String {
        val assets = portfolio.optJSONArray("assets") ?: JSONArray()
        val categories = mutableSetOf<String>()
        var includedTargets = 0.0
        for (index in 0 until assets.length()) {
            val item = assets.optJSONObject(index) ?: continue
            val category = item.optString("category", "").trim()
            if (category.isNotBlank()) {
                categories.add(category)
            }
            if (item.optBoolean("include_in_target", false)) {
                includedTargets += item.optDouble("target_percent", 0.0)
            }
        }
        return String.format(
            Locale.US,
            "%d assets • %d categories • targets %.1f%%",
            assets.length(),
            categories.size,
            includedTargets
        )
    }

    private fun <T> runStorageOperation(
        label: String,
        showWorking: Boolean = true,
        task: () -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (priceUpdateInProgress.get() || !cloudOperationInProgress.compareAndSet(false, true)) {
            if (showWorking) {
                Toast.makeText(this, ui("Another backup or sync operation is still running."), Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (showWorking) {
            Toast.makeText(this, ui(label) + "…", Toast.LENGTH_SHORT).show()
        }

        try {
            cloudExecutor.execute {
                try {
                    val result = task()
                    runOnUiThread {
                        cloudOperationInProgress.set(false)
                        if (!isFinishing && !isDestroyed) {
                            onSuccess(result)
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        cloudOperationInProgress.set(false)
                        if (!isFinishing && !isDestroyed) {
                            onFailure(error)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            cloudOperationInProgress.set(false)
            onFailure(error)
        }
    }

    private fun readUriText(uri: android.net.Uri): String {
        val stream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not read the selected file.")
        return stream.bufferedReader().use { reader ->
            val buffer = CharArray(8192)
            val result = StringBuilder()
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(result.length + count <= 20_000_000) {
                    "Backup exceeds the 20 MB safety limit. Nothing was changed."
                }
                result.append(buffer, 0, count)
            }
            result.toString()
        }
    }

    private fun writeUriText(uri: android.net.Uri, raw: String) {
        val stream = contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("Could not open the selected file for writing.")
        stream.bufferedWriter().use { writer ->
            writer.write(raw)
            writer.flush()
        }
    }

    private fun preserveCloudBeforeWrite(uri: android.net.Uri, raw: String) {
        PortfolioSafety.validateBackup(raw).also {
            require(it.kind == PortfolioSafety.BackupKind.SHARED)
        }
        val file = AtomicFile(File(filesDir, cloudPreWriteFileName))
        val payload = JSONObject().put("uri", uri.toString()).put("raw", raw)
            .toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(payload)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun recoverPreviousCloudFile() {
        val uri = loadCloudBackupUri() ?: return
        AlertDialog.Builder(this)
            .setTitle(ui("Recover Previous Cloud File"))
            .setMessage(ui("Recover only if the connected cloud file is damaged. A valid cloud file will not be replaced."))
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Recover")) { _, _ ->
                runStorageOperation(
                    label = "Recovering cloud file",
                    task = {
                        val file = AtomicFile(File(filesDir, cloudPreWriteFileName))
                        val saved = JSONObject(file.openRead().bufferedReader().use { it.readText() })
                        require(saved.optString("uri") == uri.toString()) {
                            "Recovery copy belongs to another cloud file."
                        }
                        val raw = saved.getString("raw")
                        PortfolioSafety.validateBackup(raw).also {
                            require(it.kind == PortfolioSafety.BackupKind.SHARED)
                        }
                        val current = readUriText(uri)
                        val currentValid = try {
                            PortfolioSafety.validateBackup(current).kind == PortfolioSafety.BackupKind.SHARED
                        } catch (_: Exception) {
                            false
                        }
                        require(!currentValid) {
                            "Connected cloud file is valid; recovery did not overwrite it."
                        }
                        writeUriText(uri, raw)
                        check(readUriText(uri) == raw) { "Cloud provider did not confirm the restored file." }
                        Unit
                    },
                    onSuccess = {
                        Toast.makeText(this, ui("Previous cloud file recovered."), Toast.LENGTH_LONG).show()
                    },
                    onFailure = { showCloudAccessError("Cloud Recovery Failed", it.message ?: "Recovery failed.") }
                )
            }
            .show()
    }

    private data class SmartSyncResult(
        val raw: String,
        val remote: JSONObject,
        val local: JSONObject,
        val decision: PortfolioSafety.SyncDecision
    )

    private fun smartCloudSyncCheck() {
        if (!isCloudAutoSyncEnabled()) {
            return
        }

        val uri = loadCloudBackupUri() ?: return
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(cloudLastAutoCheckKey, 0L)
        val minCheckGap = loadCloudAutoSyncMinutes() * 60_000L
        if (now - lastCheck < minCheckGap) {
            return
        }

        runStorageOperation(
            label = "Checking cloud",
            showWorking = false,
            task = {
                val raw = readUriText(uri)
                val validated = PortfolioSafety.validateBackup(raw)
                require(validated.kind == PortfolioSafety.BackupKind.SHARED) {
                    "Connected cloud file is not a shared portfolio."
                }
                val remote = validated.sharedPortfolio
                    ?: throw IllegalArgumentException("Shared portfolio data is missing.")
                val local = buildSharedPortfolio()
                val baseline = prefs.getString(cloudSharedFingerprintKey, null)
                SmartSyncResult(
                    raw = raw,
                    remote = remote,
                    local = local,
                    decision = PortfolioSafety.decideSync(
                        sharedFingerprint(local),
                        sharedFingerprint(remote),
                        baseline
                    )
                )
            },
            onSuccess = { result ->
                // A check is throttled only after a complete, valid provider read.
                prefs.edit().putLong(cloudLastAutoCheckKey, now).apply()
                when (result.decision) {
                    PortfolioSafety.SyncDecision.MATCH -> {
                        val remoteHistory = JSONObject(result.raw).optJSONObject("androidBackup")
                        val historyMatches = try {
                            PortfolioSafety.historyEquivalent(buildAndroidBackupPayload(), remoteHistory)
                        } catch (_: IllegalArgumentException) {
                            false  // Report a conflict rather than crashing the UI callback.
                        }
                        if (historyMatches) {
                            saveCloudBaseline(result.remote)
                            markCloudSync()
                        } else {
                            Toast.makeText(
                                this,
                                ui("Portfolio holdings match, but Android history differs. Use Cloud Sync to resolve it; no data was overwritten."),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    PortfolioSafety.SyncDecision.LOAD_REMOTE -> {
                        applyCloudRaw(result.raw, sharedFingerprint(result.local))
                    }
                    PortfolioSafety.SyncDecision.UPLOAD_LOCAL -> syncToCloud()
                    PortfolioSafety.SyncDecision.FIRST_SYNC_CONFLICT,
                    PortfolioSafety.SyncDecision.CONFLICT -> {
                        val changedRows = sharedChangeCount(result.local, result.remote)
                        AlertDialog.Builder(this)
                            .setTitle(ui("Cloud Sync Conflict"))
                            .setMessage(
                                ui("Smart sync found changes on both Phone and Cloud. ") +
                                    changedRows + ui(" asset row(s) differ. Nothing was overwritten.")
                            )
                            .setNegativeButton(ui("Later"), null)
                            .setNeutralButton(ui("Use Cloud")) { _, _ -> loadFromCloud() }
                            .setPositiveButton(ui("Use Phone")) { _, _ ->
                                syncToCloud(forcePhoneData = true)
                            }
                            .show()
                    }
                }
            },
            onFailure = {
                // Smart sync remains best-effort and retries on the next scheduled check.
            }
        )
    }

    private fun showCloudAccessError(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(ui(title))
            .setMessage(
                ui(message) +
                    "\n\n" + ui("If the Google Drive file was moved, removed, or access expired, reconnect it.")
            )
            .setNegativeButton(ui("Close"), null)
            .setPositiveButton(ui("Reconnect")) { _, _ ->
                connectExistingCloudBackup()
            }
            .show()
    }

    private data class CloudStatusResult(
        val local: JSONObject,
        val remote: JSONObject,
        val baseline: String?,
        val localFingerprint: String,
        val remoteFingerprint: String
    )

    private fun checkCloudStatus() {
        val uri = loadCloudBackupUri()
        if (uri == null) {
            showCloudBackupDialog()
            return
        }

        runStorageOperation(
            label = "Checking cloud status",
            task = {
                val validated = PortfolioSafety.validateBackup(readUriText(uri))
                require(validated.kind == PortfolioSafety.BackupKind.SHARED) {
                    "Connected file is not a shared portfolio."
                }
                val remote = validated.sharedPortfolio
                    ?: throw IllegalArgumentException("Shared portfolio data is missing.")
                val local = buildSharedPortfolio()
                val baseline = getSharedPreferences(prefsName, MODE_PRIVATE)
                    .getString(cloudSharedFingerprintKey, null)
                CloudStatusResult(
                    local = local,
                    remote = remote,
                    baseline = baseline,
                    localFingerprint = sharedFingerprint(local),
                    remoteFingerprint = sharedFingerprint(remote)
                )
            },
            onSuccess = { result ->
                if (
                    result.localFingerprint == result.remoteFingerprint &&
                    result.baseline != result.localFingerprint
                ) {
                    saveCloudBaseline(result.remote)
                    markCloudSync()
                }
                val state = when (
                    PortfolioSafety.decideSync(
                        result.localFingerprint,
                        result.remoteFingerprint,
                        result.baseline
                    )
                ) {
                    PortfolioSafety.SyncDecision.MATCH -> "Phone and Cloud match."
                    PortfolioSafety.SyncDecision.FIRST_SYNC_CONFLICT -> "First sync needs a choice."
                    PortfolioSafety.SyncDecision.LOAD_REMOTE -> "Cloud has newer/different portfolio data."
                    PortfolioSafety.SyncDecision.UPLOAD_LOCAL -> "Phone has changes waiting to upload."
                    PortfolioSafety.SyncDecision.CONFLICT -> "Conflict: both Phone and Cloud changed."
                }
                val changed = if (result.localFingerprint == result.remoteFingerprint) {
                    0
                } else {
                    sharedChangeCount(result.local, result.remote)
                }
                AlertDialog.Builder(this)
                    .setTitle(ui("Cloud Status"))
                    .setMessage(
                        ui(state) +
                            "\n\n" + ui("Phone: ") + sharedPortfolioSummary(result.local) +
                            "\n" + ui("Cloud: ") + sharedPortfolioSummary(result.remote) +
                            "\n" + ui("Changed asset rows: ") + changed
                    )
                    .setNegativeButton(ui("Close"), null)
                    .setPositiveButton(ui("Sync Now")) { _, _ -> syncToCloud() }
                    .show()
            },
            onFailure = { error ->
                showCloudAccessError(
                    "Cloud Status Failed",
                    error.message ?: "Could not check the cloud file."
                )
            }
        )
    }

    private enum class CloudSyncAction {
        MATCH,
        UPLOADED,
        LOAD_REMOTE,
        CONFLICT
    }

    private data class CloudSyncResult(
        val action: CloudSyncAction,
        val raw: String? = null,
        val local: JSONObject? = null,
        val remote: JSONObject? = null,
        val savedPortfolio: JSONObject? = null,
        val historyOnly: Boolean = false
    )

    private fun syncToCloud(forcePhoneData: Boolean = false) {
        val uri = loadCloudBackupUri()
        if (uri == null) {
            showCloudBackupDialog()
            return
        }

        runStorageOperation(
            label = "Syncing safely",
            task = {
                val existingRaw = readUriText(uri)
                val validated = PortfolioSafety.validateBackup(existingRaw).also {
                    require(it.kind == PortfolioSafety.BackupKind.SHARED) {
                        "Cloud file is not a shared portfolio. No data was overwritten."
                    }
                }
                val remoteShared = validated.sharedPortfolio
                // Core projection files cannot be updated by Android holdings writes.
                remoteShared?.let(PortfolioSafety::requireEditableAndroidPortfolio)
                val localShared = buildSharedPortfolio()
                if (remoteShared != null) {
                    PortfolioSafety.ensureSafeReplacement(
                        remoteShared.getJSONArray("assets").length(),
                        localShared.getJSONArray("assets").length()
                    )
                }

                if (!forcePhoneData && remoteShared != null) {
                    val baseline = getSharedPreferences(prefsName, MODE_PRIVATE)
                        .getString(cloudSharedFingerprintKey, null)
                    when (
                        PortfolioSafety.decideSync(
                            sharedFingerprint(localShared),
                            sharedFingerprint(remoteShared),
                            baseline
                        )
                    ) {
                        PortfolioSafety.SyncDecision.MATCH -> {
                            val historyMatches = PortfolioSafety.historyEquivalent(
                                buildAndroidBackupPayload(), validated.androidPayload
                            )
                            return@runStorageOperation if (historyMatches) {
                                CloudSyncResult(
                                    action = CloudSyncAction.MATCH,
                                    remote = remoteShared
                                )
                            } else {
                                CloudSyncResult(
                                    action = CloudSyncAction.CONFLICT,
                                    local = localShared,
                                    remote = remoteShared,
                                    historyOnly = true
                                )
                            }
                        }
                        PortfolioSafety.SyncDecision.LOAD_REMOTE ->
                            return@runStorageOperation CloudSyncResult(
                                action = CloudSyncAction.LOAD_REMOTE,
                                raw = existingRaw,
                                local = localShared,
                                remote = remoteShared
                            )
                        PortfolioSafety.SyncDecision.FIRST_SYNC_CONFLICT,
                        PortfolioSafety.SyncDecision.CONFLICT ->
                            return@runStorageOperation CloudSyncResult(
                                action = CloudSyncAction.CONFLICT,
                                local = localShared,
                                remote = remoteShared
                            )
                        PortfolioSafety.SyncDecision.UPLOAD_LOCAL -> Unit
                    }
                }

                val document = mergedBackupDocument(existingRaw)
                preserveCloudBeforeWrite(uri, existingRaw)
                PortfolioSafety.requireUnchangedCloudFile(existingRaw, readUriText(uri))
                val writtenRaw = document.toString(2)
                PortfolioSafety.writeAndVerifyBackup(
                    writtenRaw,
                    write = { writeUriText(uri, it) },
                    read = { readUriText(uri) }
                )
                CloudSyncResult(
                    action = CloudSyncAction.UPLOADED,
                    savedPortfolio = document.getJSONObject("sharedPortfolio")
                )
            },
            onSuccess = { result ->
                when (result.action) {
                    CloudSyncAction.MATCH -> {
                        result.remote?.let(::saveCloudBaseline)
                        markCloudSync()
                        Toast.makeText(this, ui("Phone and Cloud already match."), Toast.LENGTH_SHORT).show()
                        if (onPortfolioScreen) showPortfolioScreen()
                    }
                    CloudSyncAction.UPLOADED -> {
                        result.savedPortfolio?.let(::saveCloudBaseline)
                        markCloudSync()
                        Toast.makeText(this, ui("Cloud backup updated safely."), Toast.LENGTH_SHORT).show()
                        if (onPortfolioScreen) showPortfolioScreen()
                    }
                    CloudSyncAction.LOAD_REMOTE -> {
                        val expectedLocal = result.local?.let(::sharedFingerprint)
                        applyCloudRaw(
                            result.raw ?: throw IllegalStateException("Cloud data is missing."),
                            expectedLocal
                        )
                    }
                    CloudSyncAction.CONFLICT -> {
                        val local = result.local
                            ?: throw IllegalStateException("Phone comparison data is missing.")
                        val remote = result.remote
                            ?: throw IllegalStateException("Cloud comparison data is missing.")
                        val changedRows = sharedChangeCount(local, remote)
                        val message = if (result.historyOnly) {
                            ui("Portfolio holdings match, but Android history differs. No data was overwritten. Choose which history to reconcile.")
                        } else {
                            ui("Both copies may contain changes. ") + changedRows +
                                ui(" asset row(s) differ. Nothing was overwritten. ") +
                                ui("Choose which portfolio to keep.")
                        }
                        AlertDialog.Builder(this)
                            .setTitle(ui("Cloud Sync Conflict"))
                            .setMessage(message)
                            .setNegativeButton(ui("Cancel"), null)
                            .setNeutralButton(ui("Use Cloud")) { _, _ -> loadFromCloud() }
                            .setPositiveButton(ui("Use Phone")) { _, _ ->
                                syncToCloud(forcePhoneData = true)
                            }
                            .show()
                    }
                }
            },
            onFailure = { error ->
                showCloudAccessError(
                    "Cloud Sync Failed",
                    error.message ?: "Could not write the backup file."
                )
            }
        )
    }

    private fun confirmLoadFromCloud() {
        AlertDialog.Builder(this)
            .setTitle(ui("Load from Cloud?"))
            .setMessage(
                ui("This will apply the shared portfolio from Cloud. Android history and settings ") +
                    "are kept when available, and an Undo checkpoint is created first."
            )
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Load")) { _, _ -> loadFromCloud() }
            .show()
    }

    private fun loadFromCloud() {
        val uri = loadCloudBackupUri()
        if (uri == null) {
            showCloudBackupDialog()
            return
        }

        val expectedLocalFingerprint = sharedFingerprint(buildSharedPortfolio())
        runStorageOperation(
            label = "Loading cloud data",
            task = {
                val raw = readUriText(uri)
                val validated = PortfolioSafety.validateBackup(raw)
                require(validated.kind == PortfolioSafety.BackupKind.SHARED) {
                    "Connected cloud file is not a shared portfolio."
                }
                raw
            },
            onSuccess = { raw -> applyCloudRaw(raw, expectedLocalFingerprint) },
            onFailure = { error ->
                showCloudAccessError(
                    "Cloud Load Failed",
                    error.message ?: "Could not read the backup file."
                )
            }
        )
    }

    private fun applyCloudRaw(raw: String, expectedLocalFingerprint: String? = null) {
        try {
            if (
                expectedLocalFingerprint != null &&
                sharedFingerprint(buildSharedPortfolio()) != expectedLocalFingerprint
            ) {
                throw IllegalStateException(
                    "Phone data changed while sync was running. Nothing was overwritten; run sync again."
                )
            }
            val validated = PortfolioSafety.validateBackup(raw)
            require(validated.kind == PortfolioSafety.BackupKind.SHARED) {
                "Connected cloud file is not a shared portfolio."
            }
            restoreBackupJson(raw, mergeLocalHistory = true)
            validated.sharedPortfolio?.let(::saveCloudBaseline)
            markCloudSync()
            Toast.makeText(this, ui("Cloud backup loaded safely."), Toast.LENGTH_SHORT).show()
            showPortfolioScreen()
        } catch (error: Exception) {
            showCloudAccessError(
                "Cloud Load Failed",
                error.message ?: "Could not apply the backup file."
            )
        }
    }

    private fun showBackupDialog() {
        val hasRecovery = getSharedPreferences(prefsName, MODE_PRIVATE)
            .contains(preRestoreBackupKey)
        val options = if (hasRecovery) {
            arrayOf("Export Backup", "Import Backup", "Restore Previous Local Data", "View Windows Core Snapshot (Read-only)")
        } else {
            arrayOf("Export Backup", "Import Backup", "View Windows Core Snapshot (Read-only)")
        }.map(::ui).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(ui("Backup / Restore"))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportBackup()
                    1 -> importBackup()
                    2 -> if (!hasRecovery) previewWindowsCoreSnapshot() else AlertDialog.Builder(this)
                        .setTitle(ui("Restore Previous Local Data?"))
                        .setMessage(ui("This recovers the local portfolio preserved immediately before the last import or cloud restore."))
                        .setNegativeButton(ui("Cancel"), null)
                        .setPositiveButton(ui("Restore")) { _, _ ->
                            try {
                                pushUndoCheckpoint()
                                restorePreviousLocalState()
                                Toast.makeText(this, ui("Previous local data restored."), Toast.LENGTH_SHORT).show()
                                showPortfolioScreen()
                            } catch (error: Exception) {
                                AlertDialog.Builder(this)
                                    .setTitle(ui("Recovery Failed"))
                                    .setMessage(ui(error.message ?: "Could not restore previous local data."))
                                    .setPositiveButton(ui("OK"), null)
                                    .show()
                            }
                        }
                        .show()
                    3 -> previewWindowsCoreSnapshot()
                }
            }
            .setNegativeButton(ui("Cancel"), null)
            .show()
    }

    private fun previewWindowsCoreSnapshot() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, corePreviewRequestCode)
    }

    /** Offline target-change *request* editor; edits are NOT applied on this device. */
    private fun showCorePolicyProposalEditor(snapshot: CoreSnapshotPreview.Summary) {
        val policy = snapshot.policy ?: return
        data class PolicyField(
            val scope: String, val id: String,
            val current: Double, val input: EditText
        )
        val rows = mutableListOf<PolicyField>()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        container.addView(TextView(this).apply {
            text = ui("Target request only. No changes are applied until Windows validates and confirms.")
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })
        fun field(label: String, scope: String, id: String, current: Double) {
            container.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(0, dp(6), 0, 0)
            })
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine(true)
                setText(current.toString())
                selectAll()
            }
            container.addView(input)
            rows.add(PolicyField(scope, id, current, input))
        }
        policy.groups.forEach { group ->
            field(ui("Category target (%)") + " — " + group.name,
                "group_target", group.id, group.target)
            policy.assets.filter { it.groupId == group.id }.forEach { asset ->
                field("    " + ui("Asset target in category (%)") + " — " + asset.name,
                    "asset_target", asset.id, asset.within)
            }
        }
        field(ui("Global tolerance (%)"), "allocation_tolerance", "", policy.tolerance)
        field(ui("Cash reserve target (Toman)"), "reserve_target", "", policy.reserve)
        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(ui("Prepare Windows target change request"))
            .setView(scroll)
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Export request (not applied)"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val changes = mutableListOf<CorePolicyProposal.Edit>()
                for (row in rows) {
                    val newValue = UiText.parseUserNumber(row.input.text.toString())
                    if (newValue == null) {
                        row.input.error = ui("Enter a valid number")
                        return@setOnClickListener
                    }
                    if (kotlin.math.abs(newValue - row.current) > 1e-9) {
                        changes.add(CorePolicyProposal.Edit(row.scope, row.id, newValue))
                    }
                }
                try {
                    val json = CorePolicyProposal.create(snapshot, changes)
                    pendingCorePolicyProposalJson = json
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "Investment-Core-policy-proposal.json")
                    }
                    dialog.dismiss()
                    startActivityForResult(intent, corePolicyProposalSaveRequestCode)
                } catch (error: Exception) {
                    Toast.makeText(
                        this, ui(error.message ?: "Invalid target request."),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        dialog.show()
    }

    /** Export an offline Core command proposal; never modify financial state. */
    private fun launchPendingCoreFinancialExport() {
        if (PendingCoreFinancialRequest.restore(pendingCoreFinancialProposalJson) == null) {
            pendingCoreFinancialProposalJson = null
            Toast.makeText(this, ui("No financial request to export."), Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "Investment-Core-financial-proposal.json")
        }
        startActivityForResult(intent, coreFinancialProposalSaveRequestCode)
    }

    private fun chooseCoreFinancialRequest(snapshot: CoreSnapshotPreview.Summary) {
        try {
            pendingCoreFinancialProposalJson = coreFinancialJournal.load()
            pendingCoreFinancialJournalError = false
        } catch (_: Exception) {
            pendingCoreFinancialJournalError = true
        }
        if (pendingCoreFinancialJournalError) {
            AlertDialog.Builder(this)
                .setTitle(ui("Unreadable pending financial request"))
                .setMessage(ui(
                    "A previously prepared financial request could not be verified. No new request may be created until you explicitly discard the damaged local draft. Nothing was applied."
                ))
                .setPositiveButton(ui("Discard damaged draft")) { _, _ ->
                    try {
                        coreFinancialJournal.discardByUser()
                        pendingCoreFinancialProposalJson = null
                        pendingCoreFinancialJournalError = false
                        showCoreFinancialCommandTypes(snapshot)
                    } catch (error: Exception) {
                        showBackupFileError(error)
                    }
                }
                .setNegativeButton(ui("Cancel"), null)
                .show()
            return
        }
        if (PendingCoreFinancialRequest.restore(pendingCoreFinancialProposalJson) != null) {
            AlertDialog.Builder(this)
                .setTitle(ui("Pending financial request"))
                .setMessage(ui(
                    "The previous request was not confirmed saved. Retry with the same ID or discard it. No transaction was executed."
                ))
                .setPositiveButton(ui("Retry same financial request")) { _, _ ->
                    launchPendingCoreFinancialExport()
                }
                .setNeutralButton(ui("Discard and create a new request")) { _, _ ->
                    try {
                        coreFinancialJournal.discardByUser()
                        pendingCoreFinancialProposalJson = null
                        showCoreFinancialCommandTypes(snapshot)
                    } catch (error: Exception) {
                        showBackupFileError(error)
                    }
                }
                .setNegativeButton(ui("Cancel"), null)
                .show()
        } else {
            pendingCoreFinancialProposalJson = null
            showCoreFinancialCommandTypes(snapshot)
        }
    }

    private fun showCoreFinancialCommandTypes(snapshot: CoreSnapshotPreview.Summary) {
        val kinds = listOf("buy", "sell", "transfer", "deposit", "withdraw")
        val labels = listOf(
            "Buy from cash account", "Sell to cash account",
            "Transfer between cash accounts", "Deposit cash", "Withdraw cash"
        )
        AlertDialog.Builder(this)
            .setTitle(ui("Prepare Windows financial request"))
            .setItems(labels.map(::ui).toTypedArray()) { _, index ->
                showCoreFinancialRequestEditor(snapshot, kinds[index])
            }
            .setNegativeButton(ui("Cancel"), null)
            .show()
    }

    private fun showCoreFinancialRequestEditor(
        snapshot: CoreSnapshotPreview.Summary, kind: String
    ) {
        val isTrade = kind == "buy" || kind == "sell"
        val isTransfer = kind == "transfer"
        val cash = snapshot.cashBalances
        val assets = snapshot.positions
        if (cash.isEmpty() || (isTrade && assets.isEmpty()) ||
            (isTransfer && cash.size < 2)) {
            Toast.makeText(
                this, ui("Windows Core snapshot lacks the accounts or assets needed."),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        form.addView(TextView(this).apply {
            text = ui("Offline simulation request; no transactions are recorded.")
            textSize = 13f
            setTextColor(PortfolioAppearance.WARNING)
            setPadding(0, 0, 0, dp(9))
        })
        fun label(title: String) {
            form.addView(TextView(this).apply {
                text = ui(title)
                textSize = 14f
                setPadding(0, dp(9), 0, dp(3))
            })
        }
        fun spinner(title: String, choices: List<String>): Spinner {
            label(title)
            val choice = Spinner(this)
            choice.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item, choices
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            form.addView(choice)
            return choice
        }
        fun input(title: String): EditText {
            label(title)
            val editor = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                hint = ui(title)
                setSingleLine(true)
            }
            form.addView(editor)
            return editor
        }
        val accountLabels = cash.map {
            it.id + " • " + it.balanceToman + " " + ui("Toman")
        }
        val account = spinner(
            if (isTransfer) "Source cash account" else "Cash account",
            accountLabels
        )
        val destination = if (isTransfer) spinner(
            "Destination cash account", accountLabels
        ).apply { setSelection(1) } else null
        val asset = if (isTrade) spinner(
            "Windows Core asset",
            assets.map { it.name + " (" + it.id + ") • " + it.quantity }
        ) else null
        val amountInput = input("Total trade amount (Toman)")
        val quantityInput = if (isTrade) input("Trade quantity") else null
        val dialog = AlertDialog.Builder(this)
            .setTitle(ui("Prepare Windows financial request"))
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Export request (not applied)"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val money = UiText.parseUserNumber(amountInput.text.toString())
                if (money == null || money <= 0.0) {
                    amountInput.error = ui("Enter a positive amount.")
                    return@setOnClickListener
                }
                val qty = if (isTrade) UiText.parseUserNumber(
                    quantityInput!!.text.toString()
                ) else null
                if (isTrade && (qty == null || qty <= 0.0)) {
                    quantityInput!!.error = ui("Enter a positive quantity.")
                    return@setOnClickListener
                }
                try {
                    val generatedRequest = CoreFinancialProposal.create(
                        snapshot,
                        CoreFinancialProposal.Command(
                            type = kind,
                            amountToman = money,
                            assetId = asset?.let { assets[it.selectedItemPosition].id },
                            accountId = cash[account.selectedItemPosition].id,
                            quantity = qty,
                            destinationAccountId = destination?.let {
                                cash[it.selectedItemPosition].id
                            }
                        )
                    )
                    // Persist and verify before the file picker opens. A failed
                    // write must not permit a silently regenerated UUID.
                    pendingCoreFinancialProposalJson = coreFinancialJournal.save(generatedRequest)
                    dialog.dismiss()
                    launchPendingCoreFinancialExport()
                } catch (error: Exception) {
                    // A disk write could have succeeded even if readback failed.
                    // Re-check journal before permitting any different request.
                    try {
                        pendingCoreFinancialProposalJson = coreFinancialJournal.load()
                    } catch (_: Exception) {
                        pendingCoreFinancialJournalError = true
                    }
                    Toast.makeText(
                        this, ui(error.message ?: "Invalid financial proposal."),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        dialog.show()
    }

    private fun exportBackup() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "InvestmentAndroid-backup.json")
        }
        startActivityForResult(intent, exportBackupRequestCode)
    }

    private fun importBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, importBackupRequestCode)
    }

    private fun sharedAssetId(asset: Asset): String {
        if (asset.sharedId.isNotBlank()) {
            return asset.sharedId
        }
        val identity = if (asset.symbol.isNotBlank()) {
            asset.symbol.uppercase(Locale.US)
        } else {
            asset.name.trim().lowercase(Locale.US)
        }
        return "android:" + asset.category.trim().lowercase(Locale.US) + ":" + identity
    }

    private fun buildSharedPortfolio(): JSONObject {
        val assets = loadAssets()
        val array = JSONArray()

        assets.forEach { asset ->
            val profitPercent = if (asset.category != "Cash" && asset.averageCost > 0.0) {
                (asset.price / asset.averageCost - 1.0) * 100.0
            } else {
                0.0
            }

            array.put(
                JSONObject().apply {
                    put("id", sharedAssetId(asset))
                    put("name", asset.name)
                    put("category", asset.category)
                    put("quantity", asset.quantity)
                    put("price_toman", asset.price)
                    put("average_cost_toman", asset.averageCost)
                    put("target_percent", asset.targetPercent)
                    put("include_in_target", asset.includeInTarget)
                    put("price_source", asset.priceSource)
                    put("symbol", asset.symbol)
                    put("unrealized_pnl_percent", profitPercent)
                    put(
                        "source_platform",
                        asset.sourcePlatform.ifBlank { "android" }
                    )
                    if (
                        asset.sourceKind.isNotBlank() ||
                        asset.sourceGroupId.isNotBlank() ||
                        asset.sourceAssetId.isNotBlank() ||
                        asset.sourceBankId.isNotBlank() ||
                        asset.sourceGroupKind.isNotBlank()
                    ) {
                        put(
                            "source",
                            JSONObject().apply {
                                if (asset.sourceKind.isNotBlank()) put("kind", asset.sourceKind)
                                if (asset.sourceGroupId.isNotBlank()) put("group_id", asset.sourceGroupId)
                                if (asset.sourceAssetId.isNotBlank()) put("asset_id", asset.sourceAssetId)
                                if (asset.sourceBankId.isNotBlank()) put("bank_id", asset.sourceBankId)
                                if (asset.sourceGroupKind.isNotBlank()) put("group_kind", asset.sourceGroupKind)
                            }
                        )
                    }
                }
            )
        }

        return JSONObject().apply {
            put("currency", "Toman")
            put("assets", array)
            put("rebalance_tolerance_percent", loadTolerance())
        }
    }

    private fun buildAndroidBackupPayload(): JSONObject {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)

        return JSONObject().apply {
            put("backupVersion", 3)
            put("createdAt", System.currentTimeMillis())
            put("assets", JSONArray(prefs.getString(assetsKey, "[]") ?: "[]"))
            put("transactions", JSONArray(prefs.getString(transactionsKey, "[]") ?: "[]"))
            put("snapshots", JSONArray(prefs.getString(snapshotsKey, "[]") ?: "[]"))
            put("tolerance", loadTolerance())
            put("displayUnit", loadDisplayUnit())
            put("summaryPeriod", loadSummaryPeriod())
            put("autoRefreshMinutes", loadAutoRefreshMinutes())
            put("categories", JSONArray(prefs.getString(categoriesKey, "[]") ?: "[]"))
        }
    }

    private fun canonicalSharedCategory(item: JSONObject): String {
        val raw = item.optString("category", "Other").trim()
        val folded = raw.lowercase(Locale.US)
        val source = item.optJSONObject("source")
        val sourceGroup = source?.optString("group_id", "")?.trim()?.lowercase(Locale.US) ?: ""
        val sourceKind = source?.optString("kind", "")?.trim()?.lowercase(Locale.US) ?: ""

        val cashAliases = setOf("cash", "cash & currencies", "cash and currencies", "currencies", "currency", "bank", "banks")
        val cryptoAliases = setOf("crypto", "cryptocurrency", "cryptocurrencies")
        val stockAliases = setOf("stock", "stocks", "share", "shares", "equity", "equities")
        val goldAliases = setOf("gold", "silver", "precious metal", "precious metals")
        val fundAliases = setOf("fund", "funds", "etf", "etfs")

        return when {
            sourceGroup == "cash" || folded in cashAliases -> "Cash"
            sourceGroup == "crypto" || sourceKind == "crypto" || folded in cryptoAliases -> "Crypto"
            sourceGroup == "stocks" || folded in stockAliases -> "Stocks"
            sourceGroup == "gold" || sourceGroup == "silver" || folded in goldAliases -> "Gold"
            sourceGroup == "fund" || folded in fundAliases -> "Fund"
            coreCategories.any { it.equals(raw, ignoreCase = true) } ->
                coreCategories.first { it.equals(raw, ignoreCase = true) }
            else -> raw.ifBlank { "Other" }
        }
    }

    private fun sharedImportKey(
        item: JSONObject,
        category: String,
        name: String,
        symbol: String
    ): String {
        val id = item.optString("id", "").trim()
        if (id.isNotBlank()) {
            return "id:" + id.lowercase(Locale.US)
        }

        val source = item.optJSONObject("source")
        val groupId = source?.optString("group_id", "")?.trim()?.lowercase(Locale.US) ?: ""
        val assetId = source?.optString("asset_id", "")?.trim()?.lowercase(Locale.US) ?: ""
        val bankId = source?.optString("bank_id", "")?.trim()?.lowercase(Locale.US) ?: ""
        if (groupId.isNotBlank() && assetId.isNotBlank()) {
            return "source-asset:$groupId:$assetId"
        }
        if (groupId.isNotBlank() && bankId.isNotBlank()) {
            return "source-bank:$groupId:$bankId"
        }

        if (symbol.isNotBlank()) {
            return "symbol:" + category.lowercase(Locale.US) + ":" + symbol.lowercase(Locale.US)
        }

        return "name:" + category.lowercase(Locale.US) + ":" + name.lowercase(Locale.US)
    }

    private data class SharedImportPlan(
        val assets: List<Asset>,
        val categories: List<String>,
        val tolerance: Double
    )

    private fun buildSharedImportPlan(
        portfolio: JSONObject,
        supplemental: JSONObject?
    ): SharedImportPlan {
        val rawAssets = portfolio.optJSONArray("assets")
            ?: throw IllegalArgumentException("Shared portfolio does not contain assets.")

        val importedByKey = linkedMapOf<String, Asset>()
        val categories = loadCategories()

        for (index in 0 until rawAssets.length()) {
            val item = rawAssets.getJSONObject(index)
            val name = item.optString("name", "Asset").trim().ifBlank { "Asset" }
            val category = canonicalSharedCategory(item)
            val quantity = item.getDouble("quantity")
            PortfolioSafety.requireSafeCashQuantity(category, quantity)
            val price = item.getDouble("price_toman")
            val averageCost = if (item.has("average_cost_toman")) {
                item.getDouble("average_cost_toman")
            } else {
                price
            }
            val target = item.optDouble("target_percent", 0.0)
            val included = item.optBoolean("include_in_target", target > 0.0)
            val symbol = item.optString("symbol", "").trim().uppercase(Locale.US)
            val source = item.optString("price_source", "Manual").let {
                if (priceSources.contains(it)) it else "Manual"
            }
            val sourceMeta = item.optJSONObject("source")

            if (categories.none { it.equals(category, ignoreCase = true) }) {
                categories.add(category)
            }

            val importedAsset = Asset(
                name = name,
                category = category,
                quantity = if (category == "Cash") 1.0 else quantity,
                price = price,
                averageCost = if (category == "Cash") price else averageCost,
                targetPercent = target,
                includeInTarget = included,
                priceSource = source,
                symbol = symbol,
                sharedId = item.optString("id", ""),
                sourcePlatform = item.optString("source_platform", "android"),
                sourceKind = sourceMeta?.optString("kind", "") ?: "",
                sourceGroupId = sourceMeta?.optString("group_id", "") ?: "",
                sourceAssetId = sourceMeta?.optString("asset_id", "") ?: "",
                sourceBankId = sourceMeta?.optString("bank_id", "") ?: "",
                sourceGroupKind = sourceMeta?.optString("group_kind", "") ?: ""
            )
            importedByKey[sharedImportKey(item, category, name, symbol)] = importedAsset
        }

        supplemental?.optJSONArray("categories")?.let { savedCategories ->
            for (index in 0 until savedCategories.length()) {
                val value = savedCategories.getString(index).trim()
                if (value.isNotBlank() && categories.none { it.equals(value, ignoreCase = true) }) {
                    categories.add(value)
                }
            }
        }

        val sharedTolerance = portfolio.optDouble(
            "rebalance_tolerance_percent",
            loadTolerance()
        )

        return SharedImportPlan(
            assets = importedByKey.values.toList(),
            categories = categories,
            tolerance = sharedTolerance
        )
    }

    private fun snapshotsWithCurrentTotal(source: JSONArray, assets: List<Asset>): JSONArray {
        val result = JSONArray()
        for (index in 0 until source.length()) {
            result.put(source.get(index))
        }
        result.put(
            JSONObject().apply {
                put("totalValue", assets.sumOf { it.value })
                put("timestamp", System.currentTimeMillis())
            }
        )
        return result
    }

    private fun applySharedBackup(
        validated: PortfolioSafety.ValidatedBackup,
        mergeLocalHistory: Boolean = false
    ) {
        val portfolio = validated.sharedPortfolio
            ?: throw IllegalArgumentException("Shared portfolio payload is missing.")
        PortfolioSafety.ensureSafeReplacement(loadAssets().size, validated.incomingAssetCount)

        val supplemental = validated.androidPayload
        val plan = buildSharedImportPlan(portfolio, supplemental)
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val localTransactions = JSONArray(prefs.getString(transactionsKey, "[]") ?: "[]")
        val remoteTransactions = supplemental?.optJSONArray("transactions") ?: JSONArray()
        val transactions = if (mergeLocalHistory) {
            PortfolioSafety.mergeHistory(localTransactions, remoteTransactions, "id")
        } else {
            supplemental?.optJSONArray("transactions") ?: localTransactions
        }
        val localSnapshots = JSONArray(prefs.getString(snapshotsKey, "[]") ?: "[]")
        val remoteSnapshots = supplemental?.optJSONArray("snapshots") ?: JSONArray()
        val sourceSnapshots = if (mergeLocalHistory) {
            PortfolioSafety.mergeHistory(localSnapshots, remoteSnapshots, "timestamp")
        } else {
            supplemental?.optJSONArray("snapshots") ?: localSnapshots
        }
        val displayUnit = supplemental?.optString("displayUnit", loadDisplayUnit())
            ?: loadDisplayUnit()
        val summaryPeriod = supplemental?.optString("summaryPeriod", loadSummaryPeriod())
            ?: loadSummaryPeriod()
        val autoRefreshMinutes = supplemental?.optInt(
            "autoRefreshMinutes",
            loadAutoRefreshMinutes()
        ) ?: loadAutoRefreshMinutes()

        preservePreRestoreState()
        pushUndoCheckpoint()
        val committed = prefs.edit()
            .putString(assetsKey, assetsToJsonArray(plan.assets).toString())
            .putString(transactionsKey, transactions.toString())
            .putString(snapshotsKey, snapshotsWithCurrentTotal(sourceSnapshots, plan.assets).toString())
            .putString(toleranceKey, plan.tolerance.toString())
            .putString(displayUnitKey, displayUnit)
            .putString(summaryPeriodKey, summaryPeriod)
            .putInt(autoRefreshMinutesKey, autoRefreshMinutes)
            .putString(categoriesKey, JSONArray(plan.categories).toString())
            .commit()

        check(committed) { "Android could not save the restored portfolio. Local data was not changed." }

        scheduleAutoRefresh()
    }

    private fun applyLegacyAndroidBackup(validated: PortfolioSafety.ValidatedBackup) {
        val root = validated.root
        PortfolioSafety.ensureSafeReplacement(loadAssets().size, validated.incomingAssetCount)
        val assets = root.getJSONArray("assets")
        val transactions = root.optJSONArray("transactions") ?: JSONArray()
        val snapshots = root.optJSONArray("snapshots") ?: JSONArray()
        val tolerance = root.optDouble("tolerance", defaultTolerancePercent)
        val displayUnit = root.optString("displayUnit", "Toman")
        val summaryPeriod = root.optString("summaryPeriod", "Month")
        val autoRefreshMinutes = root.optInt("autoRefreshMinutes", 0)
        val categories = root.optJSONArray("categories") ?: JSONArray(coreCategories)

        preservePreRestoreState()
        pushUndoCheckpoint()
        val committed = getSharedPreferences(prefsName, MODE_PRIVATE).edit()
            .putString(assetsKey, assets.toString())
            .putString(transactionsKey, transactions.toString())
            .putString(snapshotsKey, snapshots.toString())
            .putString(toleranceKey, tolerance.toString())
            .putString(displayUnitKey, displayUnit)
            .putString(summaryPeriodKey, summaryPeriod)
            .putInt(autoRefreshMinutesKey, autoRefreshMinutes)
            .putString(categoriesKey, categories.toString())
            .commit()

        check(committed) { "Android could not save the restored portfolio. Local data was not changed." }

        scheduleAutoRefresh()
    }

    private fun createBackupJson(): String {
        return JSONObject().apply {
            put("format", "investment.shared.portfolio")
            put("schemaVersion", 1)
            put("updatedAt", System.currentTimeMillis())
            put("sharedPortfolio", buildSharedPortfolio())
            put("androidBackup", buildAndroidBackupPayload())
        }.toString(2)
    }

    private fun restoreBackupJson(raw: String, mergeLocalHistory: Boolean = false) {
        val validated = PortfolioSafety.validateBackup(raw)
        when (validated.kind) {
            PortfolioSafety.BackupKind.SHARED -> {
                validated.sharedPortfolio?.let(PortfolioSafety::requireEditableAndroidPortfolio)
                applySharedBackup(validated, mergeLocalHistory)
            }
            PortfolioSafety.BackupKind.LEGACY_ANDROID -> applyLegacyAndroidBackup(validated)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) {
            if (requestCode == corePolicyProposalSaveRequestCode) pendingCorePolicyProposalJson = null
            // Keep financial JSON/UUID on picker cancel or missing URI.
            if (
                requestCode == createCloudBackupRequestCode ||
                requestCode == connectCloudBackupRequestCode
            ) {
                Toast.makeText(
                    this,
                    ui("No cloud file selected. Cloud remains disconnected."),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val uri = data?.data ?: run {
            if (requestCode == corePolicyProposalSaveRequestCode) pendingCorePolicyProposalJson = null
            // Keep financial JSON/UUID on picker cancel or missing URI.
            if (
                requestCode == createCloudBackupRequestCode ||
                requestCode == connectCloudBackupRequestCode
            ) {
                Toast.makeText(this, ui("No file was returned by Android."), Toast.LENGTH_LONG).show()
            }
            return
        }

        try {
            when (requestCode) {
                corePreviewRequestCode -> {
                    runStorageOperation(
                        label = "Inspecting Windows Core snapshot",
                        task = { CoreSnapshotPreview.inspect(readUriText(uri)) },
                        onSuccess = { summary ->
                            val scroll = ScrollView(this).apply {
                                addView(TextView(this@MainActivity).apply {
                                    text = summary.display(uiLanguage() == "fa")
                                    textSize = 14f
                                    setPadding(dp(16), dp(12), dp(16), dp(12))
                                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                                    setTextIsSelectable(true)
                                })
                            }
                            val previewDialog = AlertDialog.Builder(this)
                                .setTitle(ui("Windows Core Preview (Read-only)"))
                                .setView(scroll)
                                .setPositiveButton(ui("Close"), null)
                            if (summary.policy != null) {
                                previewDialog.setNeutralButton(
                                    ui("Prepare target request")
                                ) { _, _ -> showCorePolicyProposalEditor(summary) }
                                if (summary.cashBalances.isNotEmpty()) {
                                    previewDialog.setNegativeButton(
                                        ui("Prepare financial request")
                                    ) { _, _ -> chooseCoreFinancialRequest(summary) }
                                }
                            }
                            previewDialog.show()
                        },
                        onFailure = { error -> showBackupFileError(error) }
                    )
                }
                corePolicyProposalSaveRequestCode -> {
                    val proposal = pendingCorePolicyProposalJson
                    pendingCorePolicyProposalJson = null
                    if (proposal == null) {
                        showBackupFileError(IllegalStateException("No target request to export."))
                    } else {
                        runStorageOperation(
                            label = "Saving target change request",
                            task = {
                                PortfolioSafety.writeAndVerifyBackup(
                                    proposal,
                                    write = { writeUriText(uri, it) },
                                    read = { readUriText(uri) }
                                )
                            },
                            onSuccess = {
                                Toast.makeText(
                                    this,
                                    ui("Target request saved. No investment data changed."),
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            onFailure = { error -> showBackupFileError(error) }
                        )
                    }
                }
                coreFinancialProposalSaveRequestCode -> {
                    val request = PendingCoreFinancialRequest.restore(
                        pendingCoreFinancialProposalJson
                    )
                    if (request == null) {
                        showBackupFileError(
                            IllegalStateException("No financial request to export.")
                        )
                    } else {
                        runStorageOperation(
                            label = "Saving Windows financial request",
                            task = {
                                PortfolioSafety.writeAndVerifyBackup(
                                    request,
                                    write = { writeUriText(uri, it) },
                                    read = { readUriText(uri) }
                                )
                            },
                            onSuccess = {
                                try {
                                    // Exporting a JSON file is NOT confirmation that
                                    // Windows accepted or applied the financial event.
                                    // Retain the identical UUID until explicit discard
                                    // (future: until a verified receipt is received).
                                    check(coreFinancialJournal.load() == request) {
                                        "Financial request journal changed during export."
                                    }
                                    Toast.makeText(
                                        this,
                                        ui("Financial request file saved. Operation ID remains pending; no trade was applied."),
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (error: Exception) {
                                    showBackupFileError(error)
                                }
                            },
                            onFailure = { error ->
                                // Keep exactly the same UUID and JSON for a later retry.
                                showBackupFileError(error)
                            }
                        )
                    }
                }
                exportBackupRequestCode -> {
                    runStorageOperation(
                        label = "Exporting backup",
                        task = {
                            writeUriText(uri, createBackupJson())
                            Unit
                        },
                        onSuccess = {
                            Toast.makeText(this, ui("Backup exported safely."), Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { error -> showBackupFileError(error) }
                    )
                }

                importBackupRequestCode -> {
                    val expectedLocalFingerprint = sharedFingerprint(buildSharedPortfolio())
                    runStorageOperation(
                        label = "Validating backup",
                        task = {
                            val raw = readUriText(uri)
                            PortfolioSafety.validateBackup(raw)
                            raw
                        },
                        onSuccess = { raw ->
                            try {
                                check(sharedFingerprint(buildSharedPortfolio()) == expectedLocalFingerprint) {
                                    "Phone data changed while the file was being read. Nothing was overwritten."
                                }
                                restoreBackupJson(raw)
                                Toast.makeText(this, ui("Backup restored safely."), Toast.LENGTH_SHORT).show()
                                showPortfolioScreen()
                            } catch (error: Exception) {
                                showBackupFileError(error)
                            }
                        },
                        onFailure = { error -> showBackupFileError(error) }
                    )
                }

                createCloudBackupRequestCode -> {
                    takePersistentCloudPermission(uri, data)
                    runStorageOperation(
                        label = "Creating cloud backup",
                        task = {
                            val document = mergedBackupDocument(null)
                            PortfolioSafety.writeAndVerifyBackup(
                                document.toString(2),
                                write = { writeUriText(uri, it) },
                                read = { readUriText(uri) }
                            )
                            document
                        },
                        onSuccess = { document ->
                            saveCloudBackupUri(uri)
                            saveCloudBaseline(document.getJSONObject("sharedPortfolio"))
                            markCloudSync()
                            Toast.makeText(this, ui("Cloud backup connected and saved."), Toast.LENGTH_SHORT).show()
                            scheduleSmartCloudSync()
                            if (onPortfolioScreen) showPortfolioScreen()
                        },
                        onFailure = { error ->
                            showCloudAccessError(
                                "Cloud Setup Failed",
                                error.message ?: "Could not create the cloud backup file."
                            )
                        }
                    )
                }

                connectCloudBackupRequestCode -> {
                    takePersistentCloudPermission(uri, data)
                    runStorageOperation(
                        label = "Validating cloud backup",
                        task = {
                            val raw = readUriText(uri)
                            val selected = PortfolioSafety.validateBackup(raw)
                            require(selected.kind == PortfolioSafety.BackupKind.SHARED) {
                                "Cloud sync requires an investment.shared.portfolio file."
                            }
                            raw
                        },
                        onSuccess = {
                            saveCloudBackupUri(uri)
                            AlertDialog.Builder(this)
                                .setTitle(ui("Cloud Backup Connected"))
                                .setMessage(
                                    ui("The file is valid and connected. Load its data now or keep this phone's data? ") +
                                        ui("Nothing will be overwritten until you choose.")
                                )
                                .setNegativeButton(ui("Keep Phone Data")) { _, _ ->
                                    syncToCloud(forcePhoneData = true)
                                }
                                .setPositiveButton(ui("Load Cloud Data")) { _, _ -> loadFromCloud() }
                                .show()
                        },
                        onFailure = { error ->
                            showCloudAccessError(
                                "Cloud Connection Failed",
                                error.message ?: "Could not validate the selected cloud file."
                            )
                        }
                    )
                }
            }
        } catch (error: Exception) {
            AlertDialog.Builder(this)
                .setTitle(ui("Backup Error"))
                .setMessage(ui(error.message ?: "Could not process the backup file."))
                .setPositiveButton(ui("OK"), null)
                .show()
        }
    }

    private fun showBackupFileError(error: Exception) {
        AlertDialog.Builder(this)
            .setTitle(ui("Backup Error"))
            .setMessage(ui(error.message ?: "Could not process the backup file. Local data was not changed."))
            .setPositiveButton(ui("OK"), null)
            .show()
    }

    private fun addRecentActivity(parent: LinearLayout) {
        val transactions = loadTransactions().takeLast(5).reversed()

        parent.addView(
            TextView(this).apply {
                text = ui("Recent Activity")
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PortfolioAppearance.TEXT_PRIMARY)
                setPadding(0, dp(28), 0, dp(10))
            }
        )

        if (transactions.isEmpty()) {
            parent.addView(
                TextView(this).apply {
                    text = ui("No buy or sell transactions yet.")
                    textSize = 14f
                    setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                    setPadding(0, 0, 0, dp(12))
                }
            )
            return
        }

        transactions.forEach { transaction ->
            val details = transactionDetails(transaction)

            parent.addView(
                TextView(this).apply {
                    text = details
                    textSize = 13f
                    setTextColor(PortfolioAppearance.TEXT_SECONDARY)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        setColor(PortfolioAppearance.SURFACE)
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), PortfolioAppearance.BORDER)
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            )
        }
    }

    private fun confirmDeleteAsset(index: Int, asset: Asset) {
        AlertDialog.Builder(this)
            .setTitle(ui("Delete ") + asset.name + "?")
            .setMessage(ui("This removes the asset from your portfolio. You can undo it afterward."))
            .setNegativeButton(ui("Cancel"), null)
            .setPositiveButton(ui("Delete")) { _, _ ->
                val assets = loadAssets()
                if (index in assets.indices) {
                    pushUndoCheckpoint()
                    assets.removeAt(index)
                    saveAssets(assets)
                    showPortfolioScreen()
                }
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (onPriceCenterScreen) {
            showPortfolioScreen()
        } else if (onPortfolioScreen) {
            finish()
        } else {
            super.onBackPressed()
        }
    }
}
