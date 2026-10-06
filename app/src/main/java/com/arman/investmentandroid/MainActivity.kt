package com.arman.investmentandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    data class Asset(
        val name: String,
        val category: String,
        val quantity: Double,
        val price: Double,
        val averageCost: Double,
        val targetPercent: Double,
        val includeInTarget: Boolean
    ) {
        val value: Double
            get() = quantity * price

        val invested: Double
            get() = if (category == "Cash") value else quantity * averageCost

        val profit: Double
            get() = if (category == "Cash") 0.0 else value - invested
    }

    data class Transaction(
        val type: String,
        val assetName: String,
        val quantity: Double,
        val price: Double,
        val realizedProfit: Double,
        val timestamp: Long
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
    private val exportBackupRequestCode = 1001
    private val importBackupRequestCode = 1002
    private val categories = listOf("Cash", "Gold", "Stocks", "Crypto", "Fund", "Other")
    private val defaultTolerancePercent = 1.0
    private var onPortfolioScreen = false
    private var onPriceCenterScreen = false

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureSeedData()
        showWelcomeScreen()
    }

    private fun demoAssets(): List<Asset> =
        listOf(
            Asset("Cash", "Cash", 1.0, 250_000_000.0, 250_000_000.0, 20.0, true),
            Asset("Gold", "Gold", 1.0, 375_000_000.0, 340_000_000.0, 30.0, true),
            Asset("Stocks", "Stocks", 1.0, 250_000_000.0, 265_000_000.0, 20.0, true),
            Asset("Crypto", "Crypto", 1.0, 375_000_000.0, 330_000_000.0, 30.0, true)
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

                        assets.add(
                            Asset(
                                name = name,
                                category = category,
                                quantity = item.optDouble("quantity", 1.0),
                                price = price,
                                averageCost = averageCost,
                                targetPercent = targetPercent,
                                includeInTarget = includeInTarget
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
                                includeInTarget = true
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
                }
            )
        }

        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(assetsKey, array.toString())
            .apply()
    }

    private fun loadTransactions(): MutableList<Transaction> {
        val raw = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(transactionsKey, "[]") ?: "[]"

        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                Transaction(
                    type = item.optString("type", "BUY"),
                    assetName = item.optString("assetName", "Asset"),
                    quantity = item.optDouble("quantity", 0.0),
                    price = item.optDouble("price", 0.0),
                    realizedProfit = item.optDouble("realizedProfit", 0.0),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis())
                )
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveTransactions(transactions: List<Transaction>) {
        val trimmed = transactions.takeLast(100)
        val array = JSONArray()

        trimmed.forEach { transaction ->
            array.put(
                JSONObject().apply {
                    put("type", transaction.type)
                    put("assetName", transaction.assetName)
                    put("quantity", transaction.quantity)
                    put("price", transaction.price)
                    put("realizedProfit", transaction.realizedProfit)
                    put("timestamp", transaction.timestamp)
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
        val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
            maximumFractionDigits = 0
        }
        return formatter.format(value) + " Toman"
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
            text = "Your portfolio, one step closer to mobile."
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

        container.addView(
            TextView(this).apply {
                text = "Total Portfolio Value"
                textSize = 15f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(24), 0, dp(4))
            }
        )

        container.addView(
            TextView(this).apply {
                text = formatToman(totalValue)
                textSize = 25f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(25, 25, 25))
            }
        )

        container.addView(
            TextView(this).apply {
                text = "Invested: " + formatToman(totalInvested)
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(10), 0, dp(2))
            }
        )

        container.addView(
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
                setPadding(0, 0, 0, dp(8))
            }
        )

        if (snapshotChange != null) {
            container.addView(
                TextView(this).apply {
                    text = "Change since previous snapshot: " + formatSignedToman(snapshotChange)
                    textSize = 13f
                    setTextColor(
                        when {
                            snapshotChange > 0.0 -> Color.rgb(25, 125, 70)
                            snapshotChange < 0.0 -> Color.rgb(180, 45, 45)
                            else -> Color.GRAY
                        }
                    )
                    setPadding(0, 0, 0, dp(8))
                }
            )
        }

        container.addView(
            TextView(this).apply {
                text = String.format(Locale.US, "Target total: %.1f%%", totalTarget)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    if (kotlin.math.abs(totalTarget - 100.0) <= 0.01) {
                        Color.rgb(25, 125, 70)
                    } else {
                        Color.rgb(185, 110, 25)
                    }
                )
                setPadding(0, 0, 0, dp(4))
            }
        )

        if (kotlin.math.abs(totalTarget - 100.0) > 0.01) {
            container.addView(
                TextView(this).apply {
                    text = "Targets should add up to 100% for a complete rebalance plan."
                    textSize = 12f
                    setTextColor(Color.GRAY)
                    setPadding(0, 0, 0, dp(18))
                }
            )
        } else {
            container.addView(
                TextView(this).apply {
                    text = String.format(Locale.US, "Rebalance tolerance: ±%.1f%%", tolerance)
                    textSize = 12f
                    setTextColor(Color.GRAY)
                    setPadding(0, 0, 0, dp(18))
                }
            )
        }

        if (assets.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = "No assets yet. Tap Add Asset to create your first one."
                    textSize = 16f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, dp(12), 0, dp(22))
                }
            )
        } else {
            assets.withIndex()
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
                        parent = container,
                        asset = asset,
                        allocation = allocation,
                        targetAllocation = targetAllocation,
                        targetPortfolioValue = targetPortfolioValue,
                        tolerancePercent = tolerance,
                        index = indexedAsset.index
                    )
                }
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

        val historyButton = Button(this).apply {
            text = "Portfolio History"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { showHistoryDialog() }
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
        container.addView(addButton, buttonParams)
        container.addView(priceCenterButton, buttonParams)
        container.addView(targetsButton, buttonParams)
        container.addView(toleranceButton, buttonParams)
        container.addView(activityButton, buttonParams)
        container.addView(historyButton, buttonParams)
        container.addView(backupButton, buttonParams)
        container.addView(resetButton, buttonParams)
        container.addView(backButton, buttonParams)

        setContentView(
            ScrollView(this).apply {
                addView(container)
            }
        )
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

        val categorySpinner = Spinner(this)
        val categoryAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            categories
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        categorySpinner.adapter = categoryAdapter
        val selectedCategory = existing?.category ?: "Other"
        val categoryIndex = categories.indexOf(selectedCategory).let { if (it >= 0) it else categories.lastIndex }
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
                    else -> {
                        val assets = loadAssets()
                        val updated = Asset(
                            name,
                            category,
                            quantity,
                            price,
                            averageCost,
                            targetPercent,
                            includeTargetCheck.isChecked
                        )

                        if (isEditing && index != null && index in assets.indices) {
                            assets[index] = updated
                        } else {
                            assets.add(updated)
                        }

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
                text = asset.category
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

                            assets[index] = current.copy(
                                quantity = newQuantity,
                                price = transactionPrice,
                                averageCost = newAverageCost
                            )

                            transactions.add(
                                Transaction(
                                    type = "BUY",
                                    assetName = current.name,
                                    quantity = quantity,
                                    price = transactionPrice,
                                    realizedProfit = 0.0,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        } else {
                            val realizedProfit = (transactionPrice - current.averageCost) * quantity
                            val newQuantity = current.quantity - quantity

                            if (newQuantity <= 0.0000001) {
                                assets.removeAt(index)
                            } else {
                                assets[index] = current.copy(
                                    quantity = newQuantity,
                                    price = transactionPrice
                                )
                            }

                            transactions.add(
                                Transaction(
                                    type = "SELL",
                                    assetName = current.name,
                                    quantity = quantity,
                                    price = transactionPrice,
                                    realizedProfit = realizedProfit,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }

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
                    text = asset.name + " • " + asset.category
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
                    saveAssets(updatedAssets)
                    recordSnapshot(updatedAssets)
                    Toast.makeText(this@MainActivity, "All prices updated.", Toast.LENGTH_SHORT).show()
                    showPortfolioScreen()
                }
            }
        }

        val backButton = Button(this).apply {
            text = "Back to Portfolio"
            isAllCaps = false
            setOnClickListener { showPortfolioScreen() }
        }

        container.addView(saveButton)
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

                if (kotlin.math.abs(difference) > 0.01) {
                    val transactions = loadTransactions()
                    transactions.add(
                        Transaction(
                            type = if (difference > 0.0) "INCOME" else "EXPENSE",
                            assetName = current.name,
                            quantity = 1.0,
                            price = kotlin.math.abs(difference),
                            realizedProfit = 0.0,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    saveTransactions(transactions)
                }

                saveAssets(assets)
                recordSnapshot(assets)
                dialog.dismiss()
                showPortfolioScreen()
            }
        }

        dialog.show()
    }

    private fun showActivityDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        addRecentActivity(content)

        AlertDialog.Builder(this)
            .setTitle("Activity")
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

    private fun createBackupJson(): String {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)

        return JSONObject().apply {
            put("formatVersion", 1)
            put("createdAt", System.currentTimeMillis())
            put("assets", JSONArray(prefs.getString(assetsKey, "[]") ?: "[]"))
            put("transactions", JSONArray(prefs.getString(transactionsKey, "[]") ?: "[]"))
            put("snapshots", JSONArray(prefs.getString(snapshotsKey, "[]") ?: "[]"))
            put("tolerance", loadTolerance())
        }.toString(2)
    }

    private fun restoreBackupJson(raw: String) {
        val root = JSONObject(raw)
        val assets = root.getJSONArray("assets")
        val transactions = root.optJSONArray("transactions") ?: JSONArray()
        val snapshots = root.optJSONArray("snapshots") ?: JSONArray()
        val tolerance = root.optDouble("tolerance", defaultTolerancePercent)

        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(assetsKey, assets.toString())
            .putString(transactionsKey, transactions.toString())
            .putString(snapshotsKey, snapshots.toString())
            .putString(toleranceKey, tolerance.toString())
            .apply()
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
            val details = buildString {
                append(transaction.type)
                append(" • ")
                append(transaction.assetName)
                append(" • ")
                append(formatQuantity(transaction.quantity))
                append(" @ ")
                append(formatToman(transaction.price))
                if (transaction.type == "SELL") {
                    append("\nRealized P/L: ")
                    append(formatSignedToman(transaction.realizedProfit))
                } else if (transaction.type == "INCOME" || transaction.type == "EXPENSE") {
                    append("\nBalance change: ")
                    append(if (transaction.type == "INCOME") "+" else "-")
                    append(formatToman(transaction.price))
                }
                append("\n")
                append(formatDate(transaction.timestamp))
            }

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
