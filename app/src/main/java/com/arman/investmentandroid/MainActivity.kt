package com.arman.investmentandroid

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWelcomeScreen()
    }

    private fun showWelcomeScreen() {
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
            text = "1,250,000,000 Toman"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(25, 25, 25))
            setPadding(0, 0, 0, dp(22))
        }

        container.addView(header)
        container.addView(totalLabel)
        container.addView(totalValue)

        addAssetCard(container, "Cash", "250,000,000 Toman", "20%")
        addAssetCard(container, "Gold", "375,000,000 Toman", "30%")
        addAssetCard(container, "Stocks", "250,000,000 Toman", "20%")
        addAssetCard(container, "Crypto", "375,000,000 Toman", "30%")

        val addButton = Button(this).apply {
            text = "+ Add Asset"
            isAllCaps = false
            textSize = 16f
            setOnClickListener {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Add Asset will be implemented in the next step.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
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
        container.addView(backButton, buttonParams)

        val scroll = ScrollView(this).apply {
            addView(container)
        }

        setContentView(scroll)
    }

    private fun addAssetCard(
        parent: LinearLayout,
        name: String,
        value: String,
        allocation: String
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

        val nameView = TextView(this).apply {
            text = name
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(35, 35, 35))
        }

        val valueView = TextView(this).apply {
            text = value
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(2))
        }

        val allocationView = TextView(this).apply {
            text = "Allocation: $allocation"
            textSize = 14f
            setTextColor(Color.GRAY)
        }

        card.addView(nameView)
        card.addView(valueView)
        card.addView(allocationView)

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(12)
        }

        parent.addView(card, params)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        showWelcomeScreen()
    }
}
