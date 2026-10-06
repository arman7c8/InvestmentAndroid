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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

class MainActivity : Activity() {

    data class Asset(
        val name: String,
        val quantity: Double,
        val price: Double
    ) {
        val value: Double
            get() = quantity * price
    }

    private val prefsName = "investment_android_prefs"
    private val assetsKey = "assets_json"
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
            Asset("Cash", 1.0, 250_000_000.0),
            Asset("Gold", 1.0, 375_000_000.0),
            Asset("Stocks", 1.0, 250_000_000.0),
            Asset("Crypto", 1.0, 375_000_000.0)
        )

    private fun ensureSeedData() {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        if (!prefs.contains(assetsKey)) {
            saveAssets(demoAssets())
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
                if (item.has("quantity") && item.has("price")) {
                    assets.add(
                        Asset(
                            name = item.getString("name"),
                            quantity = item.getDouble("quantity"),
                            price = item.getDouble("price")
                        )
                    )
                } else {
                    val legacyAmount = item.optDouble("amount", 0.0)
                    assets.add(
                        Asset(
                            name = item.optString("name", "Asset"),
                            quantity = 1.0,
                            price = legacyAmount
                        )
                    )
                    migratedLegacyData = true
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
                    put("quantity", asset.quantity)
                    put("price", asset.price)
                }
            )
        }

        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(assetsKey, array.toString())
            .apply()
    }

    private fun formatToman(value: Double): String {
        val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
            maximumFractionDigits = 0
        }
        return formatter.format(value) + " Toman"
    }

    private fun formatQuantity(value: Double): String {
        return NumberFormat.getNumberInstance(Locale.US).apply {
            maximumFractionDigits = 6
            minimumFractionDigits = 0
            isGroupingUsed = true
        }.format(value)
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
        val total = assets.sumOf { it.value }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(248, 249, 250))
        }

        val header = TextView(this).apply {
            text = "My Portfolio"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(30, 30, 30))
        }

        val totalLabel = TextView(this).apply {
            text = "Total Portfolio Value"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(24), 0, dp(4))
        }

        val totalValue = TextView(this).apply {
            text = formatToman(total)
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(25, 25, 25))
            setPadding(0, 0, 0, dp(22))
        }

        container.addView(header)
        container.addView(totalLabel)
        container.addView(totalValue)

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
            assets.forEachIndexed { index, asset ->
                val allocation = if (total > 0.0) {
                    asset.value / total * 100.0
                } else {
                    0.0
                }
                addAssetCard(container, asset, allocation, index)
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
                    .setMessage("This will replace your current test assets with the original demo portfolio.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Reset") { _, _ ->
                        saveAssets(demoAssets())
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

        val quantityInput = EditText(this).apply {
            hint = "Quantity"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existing?.let { formatQuantity(it.quantity).replace(",", "") } ?: "")
        }

        val priceInput = EditText(this).apply {
            hint = "Price per unit (Toman)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existing?.let { it.price.toLong().toString() } ?: "")
        }

        form.addView(nameInput)
        form.addView(quantityInput)
        form.addView(priceInput)

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
                val quantity = quantityInput.text.toString().trim().replace(",", "").toDoubleOrNull()
                val price = priceInput.text.toString().trim().replace(",", "").toDoubleOrNull()

                when {
                    name.isEmpty() -> nameInput.error = "Enter an asset name"
                    quantity == null || quantity <= 0.0 ->
                        quantityInput.error = "Enter a quantity greater than zero"
                    price == null || price < 0.0 ->
                        priceInput.error = "Enter a valid price"
                    else -> {
                        val assets = loadAssets()
                        val updated = Asset(name, quantity, price)

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
            setOnClickListener {
                showAssetDialog(index, asset)
            }
            setOnLongClickListener {
                confirmDeleteAsset(index, asset)
                true
            }
        }

        val nameView = TextView(this).apply {
            text = asset.name
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(35, 35, 35))
        }

        val quantityView = TextView(this).apply {
            text = "Quantity: " + formatQuantity(asset.quantity)
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(7), 0, dp(1))
        }

        val priceView = TextView(this).apply {
            text = "Price: " + formatToman(asset.price)
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }

        val valueView = TextView(this).apply {
            text = "Value: " + formatToman(asset.value)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(45, 45, 45))
            setPadding(0, dp(6), 0, dp(2))
        }

        val allocationView = TextView(this).apply {
            text = String.format(Locale.US, "Allocation: %.1f%%", allocation)
            textSize = 14f
            setTextColor(Color.GRAY)
        }

        val hintView = TextView(this).apply {
            text = "Tap to edit • Long press to delete"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(8), 0, 0)
        }

        card.addView(nameView)
        card.addView(quantityView)
        card.addView(priceView)
        card.addView(valueView)
        card.addView(allocationView)
        card.addView(hintView)

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
