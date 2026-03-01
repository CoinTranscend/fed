package com.sun.feddashboard.network

import com.sun.feddashboard.model.ChartPoint
import com.sun.feddashboard.model.ComponentReading
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Calls Gemini 2.5 Flash with Google Search grounding to produce a
 * structured recession risk analysis with timing estimate.
 *
 * The prompt passes a full 48-month composite trajectory log so the model
 * analyses trend direction and slope — not just the current data point.
 */
object GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val MODEL = "gemini-2.5-flash"

    fun fetchRecessionNarrative(
        rriScore: Float,
        rriRegime: String,
        lastDataMonth: String,
        components: List<ComponentReading>,
        trajectory: List<ChartPoint>,   // 48 months oldest→newest
        apiKey: String,
    ): String? {
        if (apiKey.isBlank()) return null

        val prompt = buildAnalysisLog(rriScore, rriRegime, lastDataMonth, components, trajectory)

        val bodyJson = JSONObject()
            .put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("responseMimeType", "text/plain"))

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string().orEmpty())
                root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Prompt builder ────────────────────────────────────────────────────────

    private fun buildAnalysisLog(
        score: Float,
        regime: String,
        lastMonth: String,
        components: List<ComponentReading>,
        trajectory: List<ChartPoint>,
    ): String {
        val n = trajectory.size

        // ── Trajectory metrics ─────────────────────────────────────────────────
        val val6m  = trajectory.getOrNull(n - 7)?.value
        val val12m = trajectory.getOrNull(n - 13)?.value
        val val24m = trajectory.getOrNull(n - 25)?.value

        val monthsBelowZero = trajectory.reversed().takeWhile { it.value < 0f }.size
        val monthsInCaution = trajectory.reversed().takeWhile { it.value < -0.5f }.size
        val monthsInWarning = trajectory.reversed().takeWhile { it.value < -1.0f }.size

        // 3-month slope: linear regression of last 3 values
        val last3 = trajectory.takeLast(3).map { it.value }
        val slope3m = if (last3.size == 3) (last3[2] - last3[0]) / 2f else 0f
        val trendDesc = when {
            slope3m < -0.08f -> "deteriorating rapidly (${"%+.2f".format(slope3m)}/month)"
            slope3m < -0.02f -> "slowly deteriorating (${"%+.2f".format(slope3m)}/month)"
            slope3m > 0.08f  -> "improving rapidly (${"%+.2f".format(slope3m)}/month)"
            slope3m > 0.02f  -> "slowly improving (${"%+.2f".format(slope3m)}/month)"
            else             -> "flat (${"%+.2f".format(slope3m)}/month)"
        }
        val change12m = if (val12m != null) score - val12m else null

        // ── Trajectory table (last 24 months, condensed to every other month) ──
        val histRows = trajectory.takeLast(24).joinToString("\n") { pt ->
            val r = regimeShort(pt.value)
            "  ${pt.monthLabel.padEnd(8)}  ${"%+.2f".format(pt.value)}  $r"
        }

        // ── Component table ────────────────────────────────────────────────────
        val compRows = components.sortedBy { it.contribution }.joinToString("\n") { c ->
            val arrow = when {
                c.contribution < -0.1 -> "↓ bearish"
                c.contribution < 0.0  -> "↓ slight"
                c.contribution < 0.1  -> "→ neutral"
                else                  -> "↑ bullish"
            }
            "  %-26s  z=%+.2f  %3.0f%%  %s".format(
                c.label.take(26), c.zScore, c.weight * 100, arrow
            )
        }

        val weakest  = components.minByOrNull { it.contribution }?.label ?: "—"
        val strongest = components.maxByOrNull { it.contribution }?.label ?: "—"

        return buildString {
            appendLine("╔══════════════════════════════════════════════════════════╗")
            appendLine("  RECESSION RISK ANALYSIS LOG  ·  RRI v2 (8-series FRED)")
            appendLine("  Data through: $lastMonth")
            appendLine("╚══════════════════════════════════════════════════════════╝")
            appendLine()
            appendLine("▌ CURRENT STATUS")
            appendLine("  Composite : ${"%.2f".format(score)}  →  $regime")
            if (val6m  != null) appendLine("  6m ago    : ${"%.2f".format(val6m)}  →  ${regimeLabel(val6m)}")
            if (val12m != null) appendLine("  12m ago   : ${"%.2f".format(val12m)}  →  ${regimeLabel(val12m)}")
            if (val24m != null) appendLine("  24m ago   : ${"%.2f".format(val24m)}  →  ${regimeLabel(val24m)}")
            appendLine()
            appendLine("▌ TRAJECTORY METRICS")
            appendLine("  3-month trend              : $trendDesc")
            if (change12m != null) appendLine("  12-month change            : ${"%+.2f".format(change12m)}")
            appendLine("  Months composite < 0       : $monthsBelowZero")
            appendLine("  Months in CAUTION (< −0.5) : $monthsInCaution")
            appendLine("  Months in WARNING (< −1.0) : $monthsInWarning")
            appendLine()
            appendLine("▌ 24-MONTH COMPOSITE HISTORY  (oldest → newest)")
            appendLine(histRows)
            appendLine()
            appendLine("▌ COMPONENT Z-SCORES  (sorted weakest → strongest)")
            appendLine("  ${"SERIES".padEnd(26)}  z-SCORE   WT   SIGNAL")
            appendLine(compRows)
            appendLine("  Weakest  : $weakest")
            appendLine("  Strongest: $strongest")
            appendLine()
            appendLine("▌ HISTORICAL CALIBRATION REFERENCE")
            appendLine("  The RRI uses 60-month rolling z-scores. Historical lead times:")
            appendLine("  · LOW RISK (≥ +0.5)  → recession probability < 10%  (expansion intact)")
            appendLine("  · STABLE   (≥  0.0)  → ~20% probability, normal cycle variation")
            appendLine("  · CAUTION  (≥ −0.5)  → ~40%, warning signs; recessions followed in 6–18m")
            appendLine("    e.g. RRI crossed CAUTION ~Mar 2000 → recession Mar 2001 (12m lag)")
            appendLine("    e.g. RRI crossed CAUTION ~Sep 2006 → recession Dec 2007 (15m lag)")
            appendLine("  · WARNING  (≥ −1.0)  → ~70%, elevated risk; 3–12m historical lead")
            appendLine("  · CRITICAL (< −1.0)  → > 85%, recession likely within 0–9m")
            appendLine("  Note: 2020 COVID was exogenous — RRI gave only ~1–2m warning.")
            appendLine()
            appendLine("▌ TASK  (use Google Search grounding for current economic context)")
            appendLine()
            appendLine("  Based on the trajectory data above and your search for current macro")
            appendLine("  conditions, provide a structured analysis in exactly 4 paragraphs:")
            appendLine()
            appendLine("  1. DRIVERS: What 2–3 factors (components or macro conditions) are")
            appendLine("     most responsible for the current risk level?")
            appendLine()
            appendLine("  2. RELIEF: What is preventing the composite from deteriorating further?")
            appendLine("     What would need to break for the signal to worsen materially?")
            appendLine()
            appendLine("  3. TIMING ESTIMATE: Given the trajectory slope, regime duration,")
            appendLine("     historical lead times, and current macro news — state a specific")
            appendLine("     estimated range of months to potential recession onset if current")
            appendLine("     trajectory continues unchanged (e.g. '9–18 months'). Also state")
            appendLine("     one condition that would accelerate and one that would delay it.")
            appendLine()
            appendLine("  4. WATCHLIST: Name 2–3 specific upcoming data releases or macro")
            appendLine("     events to monitor most closely in the next 60 days.")
            appendLine()
            appendLine("  Format: Plain text, 4 paragraphs labeled '1.', '2.', '3.', '4.'")
            appendLine("  Tone: Professional economist. No hedging disclaimers. ~180 words total.")
        }.trimEnd()
    }

    private fun regimeLabel(v: Float) = when {
        v >= 0.5f  -> "LOW RISK"
        v >= 0.0f  -> "STABLE"
        v >= -0.5f -> "CAUTION"
        v >= -1.0f -> "WARNING"
        else       -> "CRITICAL"
    }

    private fun regimeShort(v: Float) = when {
        v >= 0.5f  -> "LOW RISK"
        v >= 0.0f  -> "STABLE  "
        v >= -0.5f -> "CAUTION "
        v >= -1.0f -> "WARNING "
        else       -> "CRITICAL"
    }
}
