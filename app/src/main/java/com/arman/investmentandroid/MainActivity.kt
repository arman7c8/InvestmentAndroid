package com.arman.investmentandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
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
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.util.UUID

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
        val symbol: String
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
    private val exportBackupRequestCode = 1001
    private val importBackupRequestCode = 1002
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureSeedData()

        if (isAppLockEnabled()) {
            showLockedScreen()
            showStartupUnlockDialog()
        } else {
            showWelcomeScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        scheduleAutoRefresh()
    }

    override fun onPause() {
        stopAutoRefresh()
        super.onPause()
    }

    private fun demoAssets(): List<Asset> =
        listOf(
            Asset("Cash", "Cash", 1.0, 250_000_000.0, 250_000_000.0, 20.0, true, "Manual", ""),
            Asset("Gold", "Gold", 1.0, 375_000_000.0, 340_000_000.0, 30.0, true, "Manual", ""),
            Asset("Stocks", "Stocks", 1.0, 250_000_000.0, 265_000_000.0, 20.0, true, "Manual", ""),
            Asset("Crypto", "Crypto", 1.0, 375_000_000.0, 330_000_000.0, 30.0, true, "Manual", "")
        )

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
            saveAssets(demoAssets())
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
            .apply()
    }

    private fun undoLastChange() {
        val undo = loadStateStack(undoStackKey)
        if (undo.isEmpty()) {
            Toast.makeText(this, "Nothing to undo.", Toast.LENGTH_SHORT).show()
            return
        }

        val redo = loadStateStack(redoStackKey)
        redo.add(capturePortfolioState())
        val previous = undo.removeAt(undo.lastIndex)

        saveStateStack(undoStackKey, undo)
        saveStateStack(redoStackKey, redo)
        restorePortfolioState(previous)
        showPortfolioScreen()
        Toast.makeText(this, "Change undone.", Toast.LENGTH_SHORT).show()
    }

    private fun redoLastChange() {
        val redo = loadStateStack(redoStackKey)
        if (redo.isEmpty()) {
            Toast.makeText(this, "Nothing to redo.", Toast.LENGTH_SHORT).show()
            return
        }

        val undo = loadStateStack(undoStackKey)
        undo.add(capturePortfolioState())
        val next = redo.removeAt(redo.lastIndex)

        saveStateStack(undoStackKey, undo)
        saveStateStack(redoStackKey, redo)
        restorePortfolioState(next)
        showPortfolioScreen()
        Toast.makeText(this, "Change restored.", Toast.LENGTH_SHORT).show()
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

                        assets.add(
                            Asset(
                                name = name,
                                category = category,
                                quantity = item.optDouble("quantity", 1.0),
                                price = price,
                                averageCost = averageCost,
                                targetPercent = targetPercent,
                                includeInTarget = includeInTarget,
                                priceSource = priceSource,
                                symbol = symbol
                            )
                        )
                    }

                    else -> {
                        val legacyAmount = item.optDouble("amount", 0.0)
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
            return mutableListOf()
        }

        if (migratedLegacyData) {
            saveAssets(assets)
        }

        return assets
    }

    private fun saveAssets(assets: List<Asset>) {
        val array = JSONArray()
        assets.forEach { asset ->
            array.put(
                JSONObject().apply {
                    put("name", asset.name)
                    put("category", asset.category)
                    put("quantity", asset.quantity)
                    put("price", asset.price)
                    put("averageCost", asset.averageCost)
                    put("targetPercent", asset.targetPercent)
                    put("includeInTarget", asset.includeInTarget)
                    put("priceSource", asset.priceSource)
                    put("symbol", asset.symbol)
                }
            )
        }

        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(assetsKey, array.toString())
            .apply()
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
                symbol = item.optString("symbol", "")
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
        val trimmed = transactions.takeLast(100)
        val array = JSONArray()

        trimmed.forEach { transaction ->
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
        snapshots.takeLast(100).forEach { snapshot ->
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
                suffix = " Rial"
                decimals = 0
            }

            else -> {
                scaledValue = value
                suffix = " Toman"
                decimals = 0
            }
        }

        val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
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
        return raw?.toDoubleOrNull() ?: defaultTolerancePercent
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
        return digest.joinToString("") { byte -> "%02x".format(byte) }
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
            setBackgroundColor(Color.rgb(248, 249, 250))
        }

        root.addView(
            TextView(this).apply {
                text = "Investment Android"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(35, 35, 35))
            }
        )

        root.addView(
            TextView(this).apply {
                text = "App Locked"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.GRAY)
                setPadding(0, dp(12), 0, 0)
            }
        )

        setContentView(root)
    }

    private fun pinInput(): EditText {
        return EditText(this).apply {
            hint = "4–8 digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(20), dp(8), dp(20), 0)
        }
    }

    private fun showStartupUnlockDialog() {
        val input = pinInput()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Unlock Investment")
            .setMessage("Enter your app PIN.")
            .setView(input)
            .setPositiveButton("Unlock", null)
            .create()

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (verifyPin(pin)) {
                    dialog.dismiss()
                    showWelcomeScreen()
                } else {
                    input.error = "Incorrect PIN"
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
            hint = "Confirm PIN"
        }
        form.addView(pin)
        form.addView(confirm)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isAppLockEnabled()) "Change App PIN" else "Enable App Lock")
            .setMessage("Use a 4–8 digit PIN. The PIN itself is not stored.")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = pin.text.toString()
                val confirmation = confirm.text.toString()

                when {
                    value.length !in 4..8 || value.any { !it.isDigit() } ->
                        pin.error = "PIN must contain 4–8 digits"
                    value != confirmation ->
                        confirm.error = "PINs do not match"
                    else -> {
                        savePin(value)
                        dialog.dismiss()
                        Toast.makeText(this, "App lock enabled.", Toast.LENGTH_SHORT).show()
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
            .setTitle("Verify Current PIN")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (verifyPin(input.text.toString())) {
                    dialog.dismiss()
                    action()
                } else {
                    input.error = "Incorrect PIN"
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
            .setTitle("App Lock")
            .setMessage("App lock is enabled.")
            .setItems(arrayOf("Change PIN", "Remove App Lock")) { _, which ->
                when (which) {
                    0 -> verifyCurrentPinThen { showSetPinDialog() }
                    1 -> verifyCurrentPinThen {
                        removePin()
                        Toast.makeText(this, "App lock removed.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Close", null)
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

    private fun showWelcomeScreen() {
        onPortfolioScreen = false
        onPriceCenterScreen = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(248, 249, 250))
        }

        val title = TextView(this).apply {
            text = "Investment Android"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(35, 35, 35))
        }

        val subtitle = TextView(this).apply {
            text = "Your portfolio, one step closer to mobile.\nv0.16.0"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(18), 0, dp(28))
        }

        val startButton = Button(this).apply {
            text = "Open Portfolio"
            isAllCaps = false
            textSize = 17f
            setOnClickListener { showPortfolioScreen() }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(
            startButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
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
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(248, 249, 250))
        }

        container.addView(
            TextView(this).apply {
                text = "My Portfolio"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(30, 30, 30))
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

        addCategorySummary(container, assets, totalValue)
        addPeriodSummary(container, loadSummaryPeriod())



        if (assets.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = "No assets yet. Tap Add Asset to create your first one."
                    textSize = 16f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, dp(14), 0, dp(22))
                }
            )
        } else {
            addGroupedHoldings(
                parent = container,
                assets = assets,
                totalValue = totalValue,
                targetPortfolioValue = targetPortfolioValue,
                tolerance = tolerance
            )
        }

        val addButton = Button(this).apply {
            text = "+ Add Asset"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showAssetDialog() }
        }

        val priceCenterButton = Button(this).apply {
            text = "Price Center"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showPriceCenterScreen() }
        }

        val targetsButton = Button(this).apply {
            text = "Edit Targets"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showTargetsDialog() }
        }

        val toleranceButton = Button(this).apply {
            text = String.format(Locale.US, "Tolerance: ±%.1f%%", tolerance)
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showToleranceDialog() }
        }

        val activityButton = Button(this).apply {
            text = "Activity"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showActivityDialog() }
        }

        val moreToolsButton = Button(this).apply {
            text = "More Tools"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showToolsDialog() }
        }

        val historyButton = Button(this).apply {
            text = "Portfolio History"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showHistoryDialog() }
        }

        val categoriesButton = Button(this).apply {
            text = "Manage Categories"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showCategoryManagerDialog() }
        }

        val undoButton = Button(this).apply {
            text = "Undo"
            isAllCaps = false
            textSize = 16f
            isEnabled = loadStateStack(undoStackKey).isNotEmpty()
            setOnClickListener { undoLastChange() }
        }

        val redoButton = Button(this).apply {
            text = "Redo"
            isAllCaps = false
            textSize = 16f
            isEnabled = loadStateStack(redoStackKey).isNotEmpty()
            setOnClickListener { redoLastChange() }
        }

        val settingsButton = Button(this).apply {
            text = "Settings"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showSettingsDialog() }
        }

        val backupButton = Button(this).apply {
            text = "Backup / Restore"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showBackupDialog() }
        }

        val resetButton = Button(this).apply {
            text = "Reset Demo Data"
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reset demo data?")
                    .setMessage("This will replace assets and clear transaction history.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Reset") { _, _ ->
                        pushUndoCheckpoint()
                        saveAssets(demoAssets())
                        saveTransactions(emptyList())
                        saveSnapshots(emptyList())
                        showPortfolioScreen()
                    }
                    .show()
            }
        }

        val backButton = Button(this).apply {
            text = "Back"
            isAllCaps = false
            setOnClickListener { showWelcomeScreen() }
        }

        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
        }

        addRebalanceSummary(container, targetAssets, targetPortfolioValue, totalTarget, tolerance)

        container.addView(
            TextView(this).apply {
                text = "Quick Actions"
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(35, 35, 35))
                setPadding(0, dp(22), 0, dp(6))
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

        container.addView(backButton, buttonParams)
        container.addView(
            TextView(this).apply {
                text = "Investment Android • v0.13.0"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.GRAY)
                setPadding(0, dp(18), 0, dp(4))
            }
        )

        setContentView(
            ScrollView(this).apply {
                addView(container)
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
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(225, 225, 225))
            }
        }

        card.addView(
            TextView(this).apply {
                text = "Total Portfolio Value"
                textSize = 13f
                setTextColor(Color.GRAY)
            }
        )

        card.addView(
            TextView(this).apply {
                text = formatToman(totalValue)
                textSize = 27f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(25, 25, 25))
                setPadding(0, dp(2), 0, dp(10))
            }
        )

        card.addView(
            TextView(this).apply {
                text = "Invested: " + formatToman(totalInvested)
                textSize = 13f
                setTextColor(Color.DKGRAY)
            }
        )

        card.addView(
            TextView(this).apply {
                text = "Unrealized P/L: " + formatSignedToman(totalProfit)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    when {
                        totalProfit > 0.0 -> Color.rgb(25, 125, 70)
                        totalProfit < 0.0 -> Color.rgb(180, 45, 45)
                        else -> Color.DKGRAY
                    }
                )
                setPadding(0, dp(2), 0, dp(8))
            }
        )

        if (snapshotChange != null) {
            card.addView(
                TextView(this).apply {
                    text = "Since previous snapshot: " + formatSignedToman(snapshotChange)
                    textSize = 13f
                    setTextColor(
                        when {
                            snapshotChange > 0.0 -> Color.rgb(25, 125, 70)
                            snapshotChange < 0.0 -> Color.rgb(180, 45, 45)
                            else -> Color.GRAY
                        }
                    )
                    setPadding(0, 0, 0, dp(6))
                }
            )
        }

        val lastPriceUpdate = loadLastPriceUpdate()
        card.addView(
            TextView(this).apply {
                text = if (lastPriceUpdate > 0L) {
                    "Prices updated: " + formatDate(lastPriceUpdate)
                } else {
                    "Prices have not been updated yet."
                }
                textSize = 12f
                setTextColor(Color.GRAY)
            }
        )

        card.addView(
            TextView(this).apply {
                text = when {
                    !targetValid -> String.format(
                        Locale.US,
                        "Portfolio Health: Fix targets (total %.1f%%)",
                        totalTarget
                    )
                    needAttention == 0 -> "Portfolio Health: On target"
                    else -> "Portfolio Health: " + needAttention + " asset(s) need attention"
                }
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    when {
                        !targetValid -> Color.rgb(185, 110, 25)
                        needAttention == 0 -> Color.rgb(25, 125, 70)
                        else -> Color.rgb(185, 110, 25)
                    }
                )
                setPadding(0, dp(9), 0, 0)
            }
        )

        parent.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
                bottomMargin = dp(8)
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
                text = "Category Breakdown"
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(35, 35, 35))
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
                            category,
                            allocation,
                            formatToman(value)
                        )
                        textSize = 13f
                        setTextColor(Color.DKGRAY)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = dp(10).toFloat()
                            setStroke(dp(1), Color.rgb(232, 232, 232))
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
                text = "Holdings"
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(35, 35, 35))
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
                        category,
                        categoryAllocation,
                        formatToman(categoryValue)
                    )
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.rgb(55, 55, 55))
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
            "Reset Demo Data"
        )

        AlertDialog.Builder(this)
            .setTitle("Portfolio Tools")
            .setItems(options) { _, which ->
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
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showResetDemoDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset demo data?")
            .setMessage("This will replace assets and clear transaction history.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                pushUndoCheckpoint()
                saveAssets(demoAssets())
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
                text = period + " Summary"
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(35, 35, 35))
                setPadding(0, dp(12), 0, dp(6))
            }
        )

        parent.addView(
            TextView(this).apply {
                text = buildString {
                    append("Buy: ")
                    append(formatToman(buyTotal))
                    append("  •  Sell: ")
                    append(formatToman(sellTotal))
                    append("\nRealized P/L: ")
                    append(formatSignedToman(realizedProfit))
                    append("\nIncome: ")
                    append(formatToman(income))
                    append("  •  Expense: ")
                    append(formatToman(expense))
                }
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.rgb(230, 230, 230))
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
            .setTitle("Categories")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == categories.size) {
                    showAddCategoryDialog()
                } else {
                    val category = categories[which]
                    if (coreCategories.contains(category)) {
                        AlertDialog.Builder(this)
                            .setTitle(category)
                            .setMessage("This is a core category used by portfolio logic. Add a custom category if you need a different label.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        showCustomCategoryActions(category)
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAddCategoryDialog() {
        val input = EditText(this).apply {
            hint = "Category name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Category")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                val categories = loadCategories()

                when {
                    name.isBlank() -> input.error = "Enter a category name"
                    categories.any { it.equals(name, ignoreCase = true) } ->
                        input.error = "Category already exists"
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
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                if (which == 0) {
                    showRenameCategoryDialog(category)
                } else {
                    confirmDeleteCategory(category)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameCategoryDialog(oldName: String) {
        val input = EditText(this).apply {
            hint = "Category name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(oldName)
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename Category")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text.toString().trim()
                val categories = loadCategories()

                when {
                    newName.isBlank() -> input.error = "Enter a category name"
                    categories.any {
                        !it.equals(oldName, ignoreCase = true) &&
                            it.equals(newName, ignoreCase = true)
                    } -> input.error = "Category already exists"
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
            .setTitle("Delete " + category + "?")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
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
                    text = textValue
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.DKGRAY)
                    setPadding(0, dp(10), 0, dp(4))
                }
            )
        }

        addLabel("Display unit")
        val unitSpinner = Spinner(this)
        val unitAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            displayUnits
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
            summaryPeriods
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
            .setTitle("Settings")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val unit = unitSpinner.selectedItem.toString()
                val period = periodSpinner.selectedItem.toString()
                val refresh = autoRefreshValues[refreshSpinner.selectedItemPosition]

                saveSettings(unit, period, refresh)
                scheduleAutoRefresh()
                dialog.dismiss()
                showPortfolioScreen()
            }
        }

        dialog.show()
    }

    private fun showTargetsDialog() {
        val assets = loadAssets()
        val targetEntries = assets.withIndex().filter { it.value.includeInTarget }
        if (targetEntries.isEmpty()) {
            Toast.makeText(this, "No assets are included in target allocation.", Toast.LENGTH_SHORT).show()
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
                    setTextColor(Color.DKGRAY)
                    setPadding(0, dp(8), 0, 0)
                }
            )

            val input = EditText(this).apply {
                hint = "Target %"
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
            .setTitle("Edit Target Allocation")
            .setMessage("Targets must add up to 100%.")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updatedTargets = mutableMapOf<Int, Double>()
                var invalid = false

                inputs.forEach { (index, input) ->
                    val value = input.text.toString().trim().replace(",", "").toDoubleOrNull()
                    if (value == null || value < 0.0 || value > 100.0) {
                        input.error = "Enter 0 to 100"
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
            hint = "Tolerance (%)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatQuantity(loadTolerance()))
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rebalance Tolerance")
            .setMessage("Assets within this distance from target are treated as on target.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim().replace(",", "").toDoubleOrNull()

                if (value == null || value < 0.0 || value > 20.0) {
                    input.error = "Enter a value from 0 to 20"
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
                text = "Rebalance Summary"
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(35, 35, 35))
                setPadding(0, dp(22), 0, dp(8))
            }
        )

        if (kotlin.math.abs(totalTarget - 100.0) > 0.01) {
            parent.addView(
                TextView(this).apply {
                    text = "Set targets to a total of 100% to activate rebalance guidance."
                    textSize = 14f
                    setTextColor(Color.GRAY)
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
                    text = "Portfolio is within tolerance. No rebalance action is needed."
                    textSize = 14f
                    setTextColor(Color.rgb(25, 125, 70))
                    setPadding(0, 0, 0, dp(10))
                }
            )
            return
        }

        actions.forEach { (name, amount, _) ->
            parent.addView(
                TextView(this).apply {
                    text = if (amount > 0.0) {
                        "Buy " + name + " • " + formatToman(amount)
                    } else {
                        "Sell " + name + " • " + formatToman(kotlin.math.abs(amount))
                    }
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.DKGRAY)
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.rgb(230, 230, 230))
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
            hint = "Asset name"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.name ?: "")
        }

        val availableCategories = loadCategories()
        val categorySpinner = Spinner(this)
        val categoryAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            availableCategories
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        categorySpinner.adapter = categoryAdapter
        val selectedCategory = existing?.category ?: "Other"
        val categoryIndex = availableCategories.indexOf(selectedCategory)
            .let { if (it >= 0) it else availableCategories.indexOf("Other").coerceAtLeast(0) }
        categorySpinner.setSelection(categoryIndex)

        val quantityInput = EditText(this).apply {
            hint = "Quantity"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existing?.let { formatQuantity(it.quantity).replace(",", "") } ?: "")
        }

        val priceInput = EditText(this).apply {
            hint = "Current price per unit (Toman)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existing?.let { it.price.toLong().toString() } ?: "")
        }

        val averageCostInput = EditText(this).apply {
            hint = "Average cost per unit (Toman)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existing?.let { it.averageCost.toLong().toString() } ?: "")
        }

        val priceSourceSpinner = Spinner(this)
        val priceSourceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            priceSources
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        priceSourceSpinner.adapter = priceSourceAdapter
        val selectedSource = existing?.priceSource ?: "Manual"
        val sourceIndex = priceSources.indexOf(selectedSource).let { if (it >= 0) it else 0 }
        priceSourceSpinner.setSelection(sourceIndex)

        val symbolInput = EditText(this).apply {
            hint = "Market symbol (e.g. BTC, ETH, SOL)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(existing?.symbol ?: "")
        }

        val includeTargetCheck = CheckBox(this).apply {
            text = "Include in target allocation"
            isChecked = existing?.includeInTarget ?: true
            setPadding(0, dp(6), 0, 0)
        }

        val targetInput = EditText(this).apply {
            hint = "Target allocation (%)"
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
            .setTitle(if (isEditing) "Edit Asset" else "Add Asset")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (isEditing) "Save" else "Add", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val category = categorySpinner.selectedItem.toString()
                val quantity = quantityInput.text.toString().trim().replace(",", "").toDoubleOrNull()
                val price = priceInput.text.toString().trim().replace(",", "").toDoubleOrNull()
                val averageCostText = averageCostInput.text.toString().trim().replace(",", "")
                val averageCost = if (averageCostText.isBlank()) price else averageCostText.toDoubleOrNull()
                val targetPercent = targetInput.text.toString().trim().replace(",", "").toDoubleOrNull()
                val priceSource = priceSourceSpinner.selectedItem.toString()
                val symbol = symbolInput.text.toString().trim().uppercase(Locale.US)

                when {
                    name.isEmpty() -> nameInput.error = "Enter an asset name"
                    quantity == null || quantity <= 0.0 ->
                        quantityInput.error = "Enter a quantity greater than zero"
                    price == null || price < 0.0 ->
                        priceInput.error = "Enter a valid current price"
                    averageCost == null || averageCost < 0.0 ->
                        averageCostInput.error = "Enter a valid average cost"
                    targetPercent == null || targetPercent < 0.0 || targetPercent > 100.0 ->
                        targetInput.error = "Target must be between 0 and 100"
                    priceSource == "Nobitex" && symbol.isBlank() ->
                        symbolInput.error = "Enter a Nobitex market symbol"
                    priceSource == "Nobitex" && category != "Crypto" ->
                        symbolInput.error = "Nobitex source is currently for Crypto assets"
                    else -> {
                        val assets = loadAssets()
                        val updated = Asset(
                            name,
                            category,
                            quantity,
                            price,
                            averageCost,
                            targetPercent,
                            includeTargetCheck.isChecked,
                            priceSource,
                            symbol
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
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(225, 225, 225))
            }
        }

        card.addView(
            TextView(this).apply {
                text = asset.name
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(35, 35, 35))
                setOnClickListener { showAssetDialog(index, asset) }
            }
        )

        card.addView(
            TextView(this).apply {
                text = buildString {
                    append(asset.category)
                    append(" • ")
                    append(asset.priceSource)
                    if (asset.symbol.isNotBlank()) {
                        append(" • ")
                        append(asset.symbol)
                    }
                }
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, dp(3), 0, dp(5))
            }
        )

        card.addView(
            TextView(this).apply {
                text = "Quantity: " + formatQuantity(asset.quantity)
                textSize = 14f
                setTextColor(Color.DKGRAY)
            }
        )

        card.addView(
            TextView(this).apply {
                text = "Price: " + formatToman(asset.price)
                textSize = 14f
                setTextColor(Color.DKGRAY)
            }
        )

        card.addView(
            TextView(this).apply {
                text = "Avg. cost: " + formatToman(asset.averageCost)
                textSize = 14f
                setTextColor(Color.DKGRAY)
            }
        )

        card.addView(
            TextView(this).apply {
                text = "Value: " + formatToman(asset.value)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(45, 45, 45))
                setPadding(0, dp(6), 0, dp(2))
            }
        )

        card.addView(
            TextView(this).apply {
                text = "P/L: " + formatSignedToman(asset.profit)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    when {
                        asset.profit > 0.0 -> Color.rgb(25, 125, 70)
                        asset.profit < 0.0 -> Color.rgb(180, 45, 45)
                        else -> Color.DKGRAY
                    }
                )
            }
        )

        card.addView(
            TextView(this).apply {
                text = if (asset.includeInTarget) {
                    String.format(
                        Locale.US,
                        "Portfolio: %.1f%%  •  Target pool: %.1f%%  •  Target: %.1f%%",
                        allocation,
                        targetAllocation,
                        asset.targetPercent
                    )
                } else {
                    String.format(Locale.US, "Portfolio: %.1f%%  •  Target: Excluded", allocation)
                }
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, dp(2), 0, dp(2))
            }
        )

        val gap = if (asset.includeInTarget) targetAllocation - asset.targetPercent else 0.0
        card.addView(
            TextView(this).apply {
                text = if (asset.includeInTarget) {
                    String.format(Locale.US, "Distance to target: %+.1f%%", gap)
                } else {
                    "Distance to target: Not applicable"
                }
                textSize = 13f
                setTextColor(
                    if (!asset.includeInTarget || kotlin.math.abs(gap) <= tolerancePercent) {
                        Color.rgb(25, 125, 70)
                    } else {
                        Color.rgb(185, 110, 25)
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
                    !asset.includeInTarget -> "Rebalance: Excluded from target"
                    asset.targetPercent <= 0.0 -> "Rebalance: No target set"
                    kotlin.math.abs(gap) <= tolerancePercent -> "Rebalance: On target"
                    rebalanceAmount > 0.0 -> "Rebalance: Buy about " + formatToman(rebalanceAmount)
                    else -> "Rebalance: Sell about " + formatToman(kotlin.math.abs(rebalanceAmount))
                }
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    if ((!asset.includeInTarget || kotlin.math.abs(gap) <= tolerancePercent) && asset.targetPercent > 0.0) {
                        Color.rgb(25, 125, 70)
                    } else {
                        Color.DKGRAY
                    }
                )
                setPadding(0, 0, 0, dp(8))
            }
        )

        val transactionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val buyButton = Button(this).apply {
            text = "Buy"
            isAllCaps = false
            setOnClickListener { showTransactionDialog(index, asset, true) }
        }

        val sellButton = Button(this).apply {
            text = "Sell"
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
                text = "Set Final Balance"
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
                text = "Tap name to edit • Long press card to delete"
                textSize = 12f
                setTextColor(Color.GRAY)
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
            hint = "Quantity"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val priceInput = EditText(this).apply {
            hint = "Transaction price per unit (Toman)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(asset.price.toLong().toString())
        }

        form.addView(quantityInput)
        form.addView(priceInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle((if (isBuy) "Buy " else "Sell ") + asset.name)
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (isBuy) "Buy" else "Sell", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val quantity = quantityInput.text.toString().trim().replace(",", "").toDoubleOrNull()
                val transactionPrice = priceInput.text.toString().trim().replace(",", "").toDoubleOrNull()

                when {
                    quantity == null || quantity <= 0.0 ->
                        quantityInput.error = "Enter a quantity greater than zero"
                    transactionPrice == null || transactionPrice < 0.0 ->
                        priceInput.error = "Enter a valid transaction price"
                    !isBuy && quantity > asset.quantity ->
                        quantityInput.error = "You only own " + formatQuantity(asset.quantity)
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
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(248, 249, 250))
        }

        container.addView(
            TextView(this).apply {
                text = "Price Center"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(30, 30, 30))
            }
        )

        container.addView(
            TextView(this).apply {
                text = "Update all current prices in one place."
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, dp(6), 0, dp(16))
            }
        )

        val inputs = mutableListOf<Pair<Int, EditText>>()

        assets.forEachIndexed { index, asset ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.rgb(230, 230, 230))
                }
            }

            row.addView(
                TextView(this).apply {
                    text = buildString {
                        append(asset.name)
                        append(" • ")
                        append(asset.category)
                        append(" • ")
                        append(asset.priceSource)
                        if (asset.symbol.isNotBlank()) {
                            append(" • ")
                            append(asset.symbol)
                        }
                    }
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.DKGRAY)
                }
            )

            val input = EditText(this).apply {
                hint = "Current price (Toman)"
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
            text = "Save All Prices"
            isAllCaps = false
            setOnClickListener {
                val updatedAssets = loadAssets()
                var invalid = false

                inputs.forEach { (index, input) ->
                    val value = input.text.toString().trim().replace(",", "").toDoubleOrNull()
                    if (value == null || value < 0.0) {
                        input.error = "Enter a valid price"
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
                    Toast.makeText(this@MainActivity, "All prices updated.", Toast.LENGTH_SHORT).show()
                    showPortfolioScreen()
                }
            }
        }

        val apiButton = Button(this).apply {
            text = "Update Nobitex Prices"
            isAllCaps = false
            setOnClickListener { updateNobitexPrices() }
        }

        val backButton = Button(this).apply {
            text = "Back to Portfolio"
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

        setContentView(
            ScrollView(this).apply {
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
            connection.setRequestProperty("User-Agent", "InvestmentAndroid/0.9")
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
                    .setTitle("Nobitex")
                    .setMessage("No Crypto assets are configured with Nobitex as their price source.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            return
        }

        if (showResult) {
            Toast.makeText(this, "Updating Nobitex prices...", Toast.LENGTH_SHORT).show()
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

                val updatedAssets = loadAssets()
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

                        if (index in updatedAssets.indices && priceToman >= 0.0) {
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

                saveAssets(updatedAssets)
                recordSnapshot(updatedAssets)
                markPriceUpdate()

                runOnUiThread {
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
                            .setTitle("Nobitex Update")
                            .setMessage(message)
                            .setPositiveButton("OK") { _, _ -> showPortfolioScreen() }
                            .show()
                    } else if (onPortfolioScreen) {
                        showPortfolioScreen()
                    }
                }
            } catch (error: Exception) {
                if (showResult) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Nobitex Update Failed")
                            .setMessage(error.message ?: "Could not update market prices.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun showCashBalanceDialog(index: Int, asset: Asset) {
        val input = EditText(this).apply {
            hint = "Final balance (Toman)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(asset.value.toLong().toString())
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Set " + asset.name + " Balance")
            .setMessage("The app will infer the difference as income or expense.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val finalBalance = input.text.toString().trim().replace(",", "").toDoubleOrNull()
                if (finalBalance == null || finalBalance < 0.0) {
                    input.error = "Enter a valid balance"
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
            append(transaction.type)
            append(" • ")
            append(transaction.assetName)
            if (transaction.type == "INCOME" || transaction.type == "EXPENSE") {
                append("\nAmount: ")
                append(formatToman(transaction.price))
            } else {
                append("\nQuantity: ")
                append(formatQuantity(transaction.quantity))
                append("\nPrice: ")
                append(formatToman(transaction.price))
            }
            if (transaction.type == "SELL") {
                append("\nRealized P/L: ")
                append(formatSignedToman(transaction.realizedProfit))
            }
            append("\n")
            append(formatDate(transaction.timestamp))
            if (!transaction.managed) {
                append("\nLegacy activity: portfolio-safe revert unavailable")
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
                .setTitle("Cannot Safely Revert")
                .setMessage(
                    "This transaction is not the latest managed change for the asset, " +
                        "or the asset has changed since it was recorded."
                )
                .setPositiveButton("OK", null)
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
        Toast.makeText(this, "Transaction reverted.", Toast.LENGTH_SHORT).show()
    }

    private fun showActivityDialog() {
        val transactions = loadTransactions()
        val assets = loadAssets()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        if (transactions.isEmpty()) {
            content.addView(
                TextView(this).apply {
                    text = "No activity yet."
                    textSize = 14f
                    setTextColor(Color.GRAY)
                }
            )
        } else {
            transactions
                .sortedByDescending { it.timestamp }
                .forEach { transaction ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = dp(10).toFloat()
                            setStroke(dp(1), Color.rgb(230, 230, 230))
                        }
                    }

                    card.addView(
                        TextView(this).apply {
                            text = transactionDetails(transaction)
                            textSize = 13f
                            setTextColor(Color.DKGRAY)
                        }
                    )

                    val canRevert = canSafelyRevertTransaction(transaction, transactions, assets)
                    val revertButton = Button(this).apply {
                        text = if (canRevert) "Revert Transaction" else "Revert Unavailable"
                        isAllCaps = false
                        isEnabled = canRevert
                        setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Revert transaction?")
                                .setMessage(
                                    "This will reverse the portfolio effect of this transaction " +
                                        "and remove it from Activity."
                                )
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Revert") { _, _ ->
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
        }

        AlertDialog.Builder(this)
            .setTitle("Activity Manager")
            .setView(
                ScrollView(this).apply {
                    addView(content)
                }
            )
            .setPositiveButton("Close", null)
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
                    text = "No portfolio snapshots yet. Save prices in Price Center to create one."
                    textSize = 14f
                    setTextColor(Color.GRAY)
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
                                append("\nChange: ")
                                append(formatSignedToman(change))
                            }
                            append("\n")
                            append(formatDate(snapshot.timestamp))
                        }
                        textSize = 13f
                        setTextColor(Color.DKGRAY)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = dp(10).toFloat()
                            setStroke(dp(1), Color.rgb(230, 230, 230))
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
            .setTitle("Portfolio History")
            .setView(
                ScrollView(this).apply {
                    addView(content)
                }
            )
            .setPositiveButton("Close", null)
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

    private fun cloudStatusText(): String {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val connected = loadCloudBackupUri() != null
        val lastSync = prefs.getLong(cloudLastSyncKey, 0L)

        return buildString {
            append(if (connected) "Cloud backup file connected." else "No cloud backup file connected.")
            if (lastSync > 0L) {
                append("\nLast sync: ")
                append(formatDate(lastSync))
            }
            append("\n\nTip: in the Android file picker, choose Google Drive to keep the backup in Drive.")
        }
    }

    private fun showCloudBackupDialog() {
        val connected = loadCloudBackupUri() != null
        val options = if (connected) {
            arrayOf(
                "Sync Now (upload this phone)",
                "Load from Cloud",
                "Choose Different Cloud File",
                "Disconnect Cloud File"
            )
        } else {
            arrayOf(
                "Create Cloud Backup File",
                "Connect Existing Backup File"
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Google Drive / Cloud Backup")
            .setMessage(cloudStatusText())
            .setItems(options) { _, which ->
                if (connected) {
                    when (which) {
                        0 -> syncToCloud()
                        1 -> confirmLoadFromCloud()
                        2 -> connectExistingCloudBackup()
                        3 -> {
                            getSharedPreferences(prefsName, MODE_PRIVATE)
                                .edit()
                                .remove(cloudBackupUriKey)
                                .remove(cloudLastSyncKey)
                                .apply()
                            Toast.makeText(this, "Cloud backup disconnected.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    when (which) {
                        0 -> createCloudBackupFile()
                        1 -> connectExistingCloudBackup()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun createCloudBackupFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "InvestmentAndroid-cloud-backup.json")
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
            type = "application/json"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, connectCloudBackupRequestCode)
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

    private fun syncToCloud() {
        val uri = loadCloudBackupUri()
        if (uri == null) {
            showCloudBackupDialog()
            return
        }

        try {
            val stream = contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("Could not open the cloud backup file for writing.")

            stream.bufferedWriter().use { writer ->
                writer.write(createBackupJson())
            }

            markCloudSync()
            Toast.makeText(this, "Cloud backup updated.", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Cloud Sync Failed")
                .setMessage(
                    (error.message ?: "Could not write the backup file.") +
                        "\n\nIf access expired, choose the cloud file again."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun confirmLoadFromCloud() {
        AlertDialog.Builder(this)
            .setTitle("Load from Cloud?")
            .setMessage(
                "This will replace the current portfolio with the connected cloud backup. " +
                    "An Undo checkpoint will be created first."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Load") { _, _ -> loadFromCloud() }
            .show()
    }

    private fun loadFromCloud() {
        val uri = loadCloudBackupUri()
        if (uri == null) {
            showCloudBackupDialog()
            return
        }

        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Could not read the cloud backup file.")

            restoreBackupJson(raw)
            markCloudSync()
            Toast.makeText(this, "Cloud backup loaded.", Toast.LENGTH_SHORT).show()
            showPortfolioScreen()
        } catch (error: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Cloud Load Failed")
                .setMessage(
                    (error.message ?: "Could not read the backup file.") +
                        "\n\nIf access expired, choose the cloud file again."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showBackupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Backup / Restore")
            .setItems(arrayOf("Export Backup", "Import Backup")) { _, which ->
                if (which == 0) {
                    exportBackup()
                } else {
                    importBackup()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            type = "application/json"
        }
        startActivityForResult(intent, importBackupRequestCode)
    }

    private fun sharedAssetId(asset: Asset): String {
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
                    put("source_platform", "android")
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

    private fun importSharedPortfolio(portfolio: JSONObject) {
        val rawAssets = portfolio.optJSONArray("assets")
            ?: throw IllegalArgumentException("Shared portfolio does not contain assets.")

        val imported = mutableListOf<Asset>()
        val categories = loadCategories()

        for (index in 0 until rawAssets.length()) {
            val item = rawAssets.getJSONObject(index)
            val name = item.optString("name", "Asset").trim().ifBlank { "Asset" }
            val category = item.optString("category", "Other").trim().ifBlank { "Other" }
            val quantity = item.optDouble("quantity", 1.0).coerceAtLeast(0.0)
            val price = item.optDouble("price_toman", 0.0).coerceAtLeast(0.0)
            val averageCost = item.optDouble("average_cost_toman", price).coerceAtLeast(0.0)
            val target = item.optDouble("target_percent", 0.0).coerceIn(0.0, 100.0)
            val included = item.optBoolean("include_in_target", target > 0.0)
            val symbol = item.optString("symbol", "").trim().uppercase(Locale.US)
            val source = item.optString("price_source", "Manual").let {
                if (priceSources.contains(it)) it else "Manual"
            }

            if (categories.none { it.equals(category, ignoreCase = true) }) {
                categories.add(category)
            }

            imported.add(
                Asset(
                    name = name,
                    category = category,
                    quantity = if (category == "Cash") 1.0 else quantity.coerceAtLeast(0.0000001),
                    price = price,
                    averageCost = if (category == "Cash") price else averageCost,
                    targetPercent = target,
                    includeInTarget = included,
                    priceSource = source,
                    symbol = symbol
                )
            )
        }

        val sharedTolerance = portfolio.optDouble(
            "rebalance_tolerance_percent",
            loadTolerance()
        ).coerceIn(0.0, 20.0)

        pushUndoCheckpoint()
        saveAssets(imported)
        saveCategories(categories)
        saveTolerance(sharedTolerance)
        recordSnapshot(imported)
    }

    private fun restoreAndroidBackupPayload(root: JSONObject) {
        val assets = root.getJSONArray("assets")
        val transactions = root.optJSONArray("transactions") ?: JSONArray()
        val snapshots = root.optJSONArray("snapshots") ?: JSONArray()
        val tolerance = root.optDouble("tolerance", defaultTolerancePercent)
        val displayUnit = root.optString("displayUnit", "Toman")
        val summaryPeriod = root.optString("summaryPeriod", "Month")
        val autoRefreshMinutes = root.optInt("autoRefreshMinutes", 0)
        val categories = root.optJSONArray("categories") ?: JSONArray(coreCategories)

        pushUndoCheckpoint()

        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(assetsKey, assets.toString())
            .putString(transactionsKey, transactions.toString())
            .putString(snapshotsKey, snapshots.toString())
            .putString(toleranceKey, tolerance.toString())
            .putString(displayUnitKey, displayUnit)
            .putString(summaryPeriodKey, summaryPeriod)
            .putInt(autoRefreshMinutesKey, autoRefreshMinutes)
            .putString(categoriesKey, categories.toString())
            .apply()

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

    private fun restoreBackupJson(raw: String) {
        val root = JSONObject(raw)

        if (root.optString("format") == "investment.shared.portfolio") {
            val androidBackup = root.optJSONObject("androidBackup")
            if (androidBackup != null) {
                restoreAndroidBackupPayload(androidBackup)
            } else {
                val sharedPortfolio = root.optJSONObject("sharedPortfolio")
                    ?: throw IllegalArgumentException("Shared portfolio payload is missing.")
                importSharedPortfolio(sharedPortfolio)
            }
            return
        }

        // Backward compatibility with Android backup v1-v3.
        restoreAndroidBackupPayload(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) {
            return
        }

        val uri = data?.data ?: return

        try {
            when (requestCode) {
                exportBackupRequestCode -> {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write(createBackupJson())
                    }
                    Toast.makeText(this, "Backup exported.", Toast.LENGTH_SHORT).show()
                }

                importBackupRequestCode -> {
                    val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalArgumentException("Could not read backup file.")
                    restoreBackupJson(raw)
                    Toast.makeText(this, "Backup restored.", Toast.LENGTH_SHORT).show()
                    showPortfolioScreen()
                }

                createCloudBackupRequestCode -> {
                    takePersistentCloudPermission(uri, data)
                    saveCloudBackupUri(uri)
                    val stream = contentResolver.openOutputStream(uri, "wt")
                        ?: throw IllegalStateException("Could not create cloud backup file.")
                    stream.bufferedWriter().use { writer ->
                        writer.write(createBackupJson())
                    }
                    markCloudSync()
                    Toast.makeText(this, "Cloud backup connected and saved.", Toast.LENGTH_SHORT).show()
                }

                connectCloudBackupRequestCode -> {
                    takePersistentCloudPermission(uri, data)
                    val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalArgumentException("Could not read selected backup file.")

                    val selected = JSONObject(raw)
                    val valid = if (selected.optString("format") == "investment.shared.portfolio") {
                        selected.optJSONObject("sharedPortfolio")
                            ?.optJSONArray("assets") != null
                    } else {
                        selected.optJSONArray("assets") != null
                    }
                    if (!valid) {
                        throw IllegalArgumentException("Selected file is not a compatible Investment backup.")
                    }
                    saveCloudBackupUri(uri)

                    AlertDialog.Builder(this)
                        .setTitle("Cloud Backup Connected")
                        .setMessage("The file is connected. Load its data now or keep this phone's data?")
                        .setNegativeButton("Keep Phone Data") { _, _ ->
                            syncToCloud()
                        }
                        .setPositiveButton("Load Cloud Data") { _, _ ->
                            loadFromCloud()
                        }
                        .show()
                }
            }
        } catch (error: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Backup Error")
                .setMessage(error.message ?: "Could not process the backup file.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun addRecentActivity(parent: LinearLayout) {
        val transactions = loadTransactions().takeLast(5).reversed()

        parent.addView(
            TextView(this).apply {
                text = "Recent Activity"
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(35, 35, 35))
                setPadding(0, dp(28), 0, dp(10))
            }
        )

        if (transactions.isEmpty()) {
            parent.addView(
                TextView(this).apply {
                    text = "No buy or sell transactions yet."
                    textSize = 14f
                    setTextColor(Color.GRAY)
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
                    setTextColor(Color.DKGRAY)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.rgb(230, 230, 230))
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
            .setTitle("Delete " + asset.name + "?")
            .setMessage("This removes the asset from this test portfolio.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
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
            showWelcomeScreen()
        } else {
            super.onBackPressed()
        }
    }
}
