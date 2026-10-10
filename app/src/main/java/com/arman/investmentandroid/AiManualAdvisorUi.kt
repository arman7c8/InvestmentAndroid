package com.arman.investmentandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Deliberately offline bridge for the AI Advisor foundation.
 *
 * A ChatGPT-plan OAuth transport is NOT enabled until this app has a
 * verified, supported authorization flow. Export is always user-initiated;
 * incoming recommendations are strictly validated and are NEVER applied
 * to Android assets, Windows Core, targets, or transaction history.
 */
class AiManualAdvisorUi(
    private val activity: Activity,
    private val snapshot: () -> JSONObject
) {
    private val journal by lazy { AiRecommendationJournal(activity) }
    private fun tr(value: String): String =
        UiText.translate(value, activity.resources.configuration.locales[0].language)

    private fun alert(message: String) {
        AlertDialog.Builder(activity)
            .setTitle(tr("AI Advisor — Preview"))
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showPrivacyInformation() {
        alert(
            "Only allocation and optional performance percentages are shared, " +
                "and only when you explicitly choose an app. No amounts, quantities, " +
                "bank details or transactions are exported. Suggestions never " +
                "change investment targets or financial records."
        )
    }

    fun show() {
        // AlertDialog.setMessage and setItems compete for its list/content panel:
        // on Android 16 the message hid every action, leaving only Close.
        // Keep the actions in the list and make the explanation a selectable
        // privacy-information item instead of a competing message.
        AlertDialog.Builder(activity)
            .setTitle(tr("AI Advisor — Offline preview"))
            .setItems(arrayOf(
                "Share privacy-safe percentages",
                "Paste recommendation JSON",
                "Recommendation history",
                "Export AI journal (manual backup)",
                "Restore AI journal (manual, conflict-checked)",
                "Privacy and safety information"
            ).map(::tr).toTypedArray()) { _, which ->
                when (which) {
                    0 -> share()
                    1 -> importRecommendation()
                    2 -> history()
                    3 -> exportJournal()
                    4 -> restoreJournal()
                    5 -> showPrivacyInformation()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun share() {
        try {
            val data = snapshot()
            AiAdvisorContract.assertSnapshotSafe(data)
            val prompt = "Review the following investment percentages only. " +
                "Do not request financial amounts or execute trades.\n" +
                AiAdvisorRecommendation.promptContract() +
                "\n\n" + data.toString(2)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, prompt)
            }
            activity.startActivity(Intent.createChooser(intent, "Share percentages for AI review"))
        } catch (error: Exception) {
            alert(error.message ?: "Could not prepare a privacy-safe AI snapshot.")
        }
    }

    private fun importRecommendation() {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
            maxLines = 16
            hint = "Paste the complete investment.ai.recommendation JSON"
            setHorizontallyScrolling(false)
        }
        val box = ScrollView(activity).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(field)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(tr("Validate offline AI suggestion"))
            .setMessage(
                "Validation checks every public key and current percentage. " +
                    "Saving a recommendation does NOT apply financial changes."
            )
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Validate and save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val current = snapshot()
                    val suggestion = AiAdvisorRecommendation.parse(field.text.toString())
                    AiManualRecommendationGate.validateAgainstSnapshot(current, suggestion)
                    val record = journal.recordRecommendation(
                        snapshot = current,
                        recommendation = suggestion,
                        model = "manual-reviewed"
                    )
                    dialog.dismiss()
                    showRecommendation(record)
                } catch (error: Exception) {
                    Toast.makeText(activity, error.message ?: "Invalid AI response.", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showRecommendation(record: JSONObject) {
        val id = record.getString("recommendation_id")
        val recommendation = record.getJSONObject("recommendation")
        val text = buildString {
            append(recommendation.optString("summary"))
            append("\n\nModel: offline / manually reviewed")
            append("\nConfidence: ")
            append(recommendation.optDouble("confidence_pct"))
            append("%")
            val rows = recommendation.getJSONArray("suggested_targets")
            for (index in 0 until rows.length()) {
                val item = rows.getJSONObject(index)
                append("\n")
                append(item.optString("public_key"))
                append(": ")
                append(item.optDouble("current_pct"))
                append("% -> ")
                append(item.optDouble("suggested_pct"))
                append("%")
            }
            append("\n\nAccept only records this advice. No targets or transactions change.")
        }
        AlertDialog.Builder(activity)
            .setTitle(tr("AI suggestion — review only"))
            .setMessage(text)
            .setPositiveButton("Accept for tracking") { _, _ ->
                decide(id, "accepted")
            }
            .setNeutralButton("Reject") { _, _ ->
                decide(id, "rejected")
            }
            .setNegativeButton("Keep pending", null)
            .show()
    }

    private fun decide(id: String, decision: String) {
        try {
            journal.setDecision(id, decision)
            Toast.makeText(
                activity,
                "Decision recorded locally. No financial changes were made.",
                Toast.LENGTH_LONG
            ).show()
        } catch (error: Exception) {
            alert(error.message ?: "Could not record AI decision.")
        }
    }

    private fun exportJournal() {
        try {
            val document = journal.exportDocument()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_TEXT, document.toString(2))
            }
            activity.startActivity(Intent.createChooser(intent, "Back up AI journal JSON"))
        } catch (error: Exception) {
            alert(error.message ?: "AI journal could not be exported.")
        }
    }

    private fun restoreJournal() {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
            hint = "Paste investment.ai.journal JSON"
            setHorizontallyScrolling(false)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(tr("Restore AI journal — local only"))
            .setMessage("Accepted/rejected conflicts block recovery. Nothing is imported into financial data.")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Validate and merge", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val document = JSONObject(field.text.toString())
                    journal.mergeDocument(document)
                    dialog.dismiss()
                    alert("AI journal merged locally. No financial data was changed.")
                } catch (error: Exception) {
                    Toast.makeText(activity, error.message ?: "AI journal conflict or invalid data.", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun history() {
        val records = try {
            journal.listRecords().asReversed()
        } catch (error: Exception) {
            alert(error.message ?: "AI journal unavailable.")
            return
        }
        if (records.isEmpty()) {
            alert("No AI recommendations recorded yet.")
            return
        }
        val text = records.take(25).joinToString("\n\n") { record ->
            record.optString("created_at").take(19) + "  •  " +
                record.optString("status").uppercase() + "\n" +
                (record.optJSONObject("recommendation")?.optString("summary") ?: "")
        }
        val scroll = ScrollView(activity).apply {
            addView(TextView(activity).apply {
                this.text = text
                textSize = 14f
                val pad = (18 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity)
            .setTitle(tr("AI recommendation journal (local only)"))
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }
}
