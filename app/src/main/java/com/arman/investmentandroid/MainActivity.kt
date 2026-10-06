package com.arman.investmentandroid

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }

        val title = TextView(this).apply {
            text = "Investment Android"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "اولین نسخه آزمایشی اندروید"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, padding, 0, padding)
        }

        val button = Button(this).apply {
            text = "شروع"
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "سلام! اپلیکیشن اندروید با موفقیت اجرا شد.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(button)

        setContentView(root)
    }
}
