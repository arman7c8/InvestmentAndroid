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

    data class Asset(val name: String, val amount: Long)

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

    private fun ensureSeedData() {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        if (!prefs.contains(assetsKey)) {
            saveAssets(
                listOf(
                    Asset("Cash", 250_000_000L),
                    Asset("Gold", 375_000_000L),
                    Asset("Stocks", 250_000_000L),
                    Asset("Crypto", 375_000_000L)
                )
            )
        }
    }

    private fun loadAssets(): MutableList<Asset> {
        val raw = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(assetsKey, "[]") ?: "[]"

        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                Asset(
                    name = item.getString("name"),
                    amount = item.getLong("amount")
                )
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveAssets(assets: List<Asset>) {
        val array = JSONArray()
        assets.forEach { asset ->
            array.put(
                JSONObject().apply {
                    put("name", asset.name)
                    put("amount", asset.amount)
                }
            )
        }

        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(assetsKey, array.toString())
            .apply()
    }

    private fun formatToman(value: Long): String {
        return NumberFormat.getNumberInstance(Locale.US).format(value) + " Toman"
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
        val total = assets.sumOf { it.amount }

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
                val allocation = if (total > 0L) {
                    asset.amount.toDouble() / total.toDouble() * 100.0
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
            setOnClickListener { showAddAssetDialog() }
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
                        saveAssets(
                            listOf(
                                Asset("Cash", 250_000_000L),
                                Asset("Gold", 375_000_000L),
                                Asset("Stocks", 250_000_000L),
                                Asset("Crypto", 375_000_000L)
                            )
                        )
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

    private fun showAddAssetDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val nameInput = EditText(this).apply {
            hint = "Asset name"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val amountInput = EditText(this).apply {
            hint = "Value in Toman"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        form.addView(nameInput)
        form.addView(amountInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Asset")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val amount = amountInput.text.toString().trim().toLongOrNull()

                when {
                    name.isEmpty() -> nameInput.error = "Enter an asset name"
                    amount == null || amount < 0L -> amountInput.error = "Enter a valid amount"
                    else -> {
                        val assets = loadAssets()
                        assets.add(Asset(name, amount))
                        saveAssets(assets)
                        dialog.dismiss()
                        showPortfolioScreen()
                        Toast.makeText(this, "$name added", Toast.LENGTH_SHORT).show()
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

        val valueView = TextView(this).apply {
            text = formatToman(asset.amount)
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(2))
        }

        val allocationView = TextView(this).apply {
            text = String.format(Locale.US, "Allocation: %.1f%%", allocation)
            textSize = 14f
            setTextColor(Color.GRAY)
        }

        val hintView = TextView(this).apply {
            text = "Long press to delete"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(7), 0, 0)
        }

        card.addView(nameView)
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
            .setTitle("Delete ${asset.name}?")
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
