package com.arman.investmentandroid

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
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
        val targetPercent: Double
    ) {
        val value: Double
            get() = quantity * price

        val invested: Double
            get() = quantity * averageCost

        val profit: Double
            get() = value - invested
    }

    data class Transaction(
        val type: String,
        val assetName: String,
        val quantity: Double,
        val price: Double,
        val realizedProfit: Double,
        val timestamp: Long
    )

    private val prefsName = "investment_android_prefs"
    private val assetsKey = "assets_json"
    private val transactionsKey = "transactions_json"
    private val categories = listOf("Cash", "Gold", "Stocks", "Crypto", "Fund", "Other")
    private val targetTolerancePercent = 1.0
    private var onPortfolioScreen = false

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureSeedData()
        showWelcomeScreen()
    }

    private fun demoAssets(): List<Asset> =
        listOf(
            Asset("Cash", "Cash", 1.0, 250_000_000.0, 250_000_000.0, 20.0),
            Asset("Gold", "Gold", 1.0, 375_000_000.0, 340_000_000.0, 30.0),
            Asset("Stocks", "Stocks", 1.0, 250_000_000.0, 265_000_000.0, 20.0),
            Asset("Crypto", "Crypto", 1.0, 375_000_000.0, 330_000_000.0, 30.0)
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

                        assets.add(
                            Asset(
                                name = name,
                                category = category,
                                quantity = item.optDouble("quantity", 1.0),
                                price = price,
                                averageCost = averageCost,
                                targetPercent = targetPercent
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
                                targetPercent = defaultTargetPercent(name, category)
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

    private fun showWelcomeScreen() {
        onPortfolioScreen = false

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
        val assets = loadAssets()
        val totalValue = assets.sumOf { it.value }
        val totalInvested = assets.sumOf { it.invested }
        val totalProfit = totalValue - totalInvested
        val totalTarget = assets.sumOf { it.targetPercent }

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
                    text = "Rebalance tolerance: ±1.0%"
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
                    addAssetCard(
                        parent = container,
                        asset = asset,
                        allocation = allocation,
                        totalPortfolioValue = totalValue,
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

        container.addView(addButton, buttonParams)
        addRecentActivity(container)
        container.addView(resetButton, buttonParams)
        container.addView(backButton, buttonParams)

        setContentView(
            ScrollView(this).apply {
                addView(container)
            }
        )
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
                        val updated = Asset(name, category, quantity, price, averageCost, targetPercent)

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
        totalPortfolioValue: Double,
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
                text = String.format(
                    Locale.US,
                    "Allocation: %.1f%%  •  Target: %.1f%%",
                    allocation,
                    asset.targetPercent
                )
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, dp(2), 0, dp(2))
            }
        )

        val gap = allocation - asset.targetPercent
        card.addView(
            TextView(this).apply {
                text = String.format(Locale.US, "Distance to target: %+.1f%%", gap)
                textSize = 13f
                setTextColor(
                    if (kotlin.math.abs(gap) <= targetTolerancePercent) {
                        Color.rgb(25, 125, 70)
                    } else {
                        Color.rgb(185, 110, 25)
                    }
                )
                setPadding(0, 0, 0, dp(3))
            }
        )

        val desiredValue = totalPortfolioValue * asset.targetPercent / 100.0
        val rebalanceAmount = desiredValue - asset.value
        card.addView(
            TextView(this).apply {
                text = when {
                    asset.targetPercent <= 0.0 -> "Rebalance: No target set"
                    kotlin.math.abs(gap) <= targetTolerancePercent -> "Rebalance: On target"
                    rebalanceAmount > 0.0 -> "Rebalance: Buy about " + formatToman(rebalanceAmount)
                    else -> "Rebalance: Sell about " + formatToman(kotlin.math.abs(rebalanceAmount))
                }
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    if (kotlin.math.abs(gap) <= targetTolerancePercent && asset.targetPercent > 0.0) {
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

        card.addView(transactionRow)

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
                        dialog.dismiss()
                        showPortfolioScreen()
                    }
                }
            }
        }

        dialog.show()
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
        if (onPortfolioScreen) {
            showWelcomeScreen()
        } else {
            super.onBackPressed()
        }
    }
}
