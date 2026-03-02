package com.sun.feddashboard.network

import com.sun.feddashboard.model.PulseResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Always-on Gemini narrative for the PULSE Consumer Stress Index.
 *
 * Called automatically alongside every FRED refresh.
 * Uses Google Search grounding to pull current consumer price trends,
 * bankruptcy filings, and food-bank/food-insecurity signals.
 *
 * Returns a [GeminiPulseResponse] with the narrative text and HTTP error
 * code (if any) so the ViewModel can map to the correct [GeminiPulseStatus].
 */
object GeminiPulseClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val MODEL = "gemini-2.5-flash"

    data class GeminiPulseResponse(
        val text: String?,
        val errorCode: Int? = null,
    )

    fun fetchNarrative(result: PulseResult, apiKey: String): GeminiPulseResponse {
        if (apiKey.isBlank()) return GeminiPulseResponse(null)

        val prompt = buildPrompt(result)

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
                if (!resp.isSuccessful) return GeminiPulseResponse(null, resp.code)
                val root = JSONObject(resp.body?.string().orEmpty())
                val text = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                GeminiPulseResponse(text)
            }
        } catch (_: Throwable) {
            GeminiPulseResponse(null)
        }
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    private fun buildPrompt(r: PulseResult): String {
        val eipComp = r.eipComponents.sortedBy { it.contribution }
            .joinToString("\n") { c ->
                "  %-22s  z=%+.2f  Q1-wt=%.1f%%".format(c.label, c.zScore, c.weight * 100)
            }
        val asiComp = r.asiComponents.sortedBy { it.contribution }
            .joinToString("\n") { c ->
                "  %-22s  z=%+.2f  wt=%.0f%%".format(c.label, c.zScore, c.weight * 100)
            }
        val fssComp = r.fssComponents.sortedBy { it.contribution }
            .joinToString("\n") { c ->
                "  %-22s  z=%+.2f  wt=%.0f%%".format(c.label, c.zScore, c.weight * 100)
            }

        val traj = r.points.takeLast(12).joinToString("\n") { pt ->
            "  ${pt.monthLabel.padEnd(8)}  ${"%+.2f".format(pt.value)}  ${regimeShort(pt.value)}"
        }

        return buildString {
            appendLine("╔══════════════════════════════════════════════════════════╗")
            appendLine("  PULSE — People's Underlying Living Stress Engine")
            appendLine("  Consumer Stress Index  ·  Data through: ${r.lastDataMonth}")
            appendLine("╚══════════════════════════════════════════════════════════╝")
            appendLine()
            appendLine("▌ CURRENT READINGS")
            appendLine("  PULSE composite : ${"%.2f".format(r.current)}  →  ${r.regime}")
            appendLine("  EIP (40%)       : ${"%.2f".format(r.eipScore)}  Essentials Inflation Pressure")
            appendLine("  ASI (35%)       : ${"%.2f".format(r.asiScore)}  Affordability Squeeze Index")
            appendLine("  FSS (25%)       : ${"%.2f".format(r.fssScore)}  Forward Stress Signal")
            appendLine()
            appendLine("▌ QUINTILE WAGE-DEFLATED BURDEN  (positive = wages outpacing prices)")
            appendLine("  Q1 (< \$35 k/yr) : ${"%.3f".format(r.q1Burden)}")
            appendLine("  Q2 (\$35–65 k)   : ${"%.3f".format(r.q2Burden)}")
            appendLine("  Q3 (\$65–105 k)  : ${"%.3f".format(r.q3Burden)}")
            appendLine()
            appendLine("▌ EIP COMPONENTS  (shelter, food, gas vs wage growth)")
            appendLine(eipComp)
            appendLine()
            appendLine("▌ ASI COMPONENTS  (debt, savings, charge-offs)")
            appendLine(asiComp)
            appendLine()
            appendLine("▌ FSS COMPONENTS  (sentiment, retail, disposable income)")
            appendLine(fssComp)
            appendLine()
            appendLine("▌ 12-MONTH COMPOSITE HISTORY")
            appendLine(traj)
            appendLine()
            appendLine("▌ INDEX CALIBRATION")
            appendLine("  RESILIENT (≥ +0.5): wages outpacing essentials; savings healthy")
            appendLine("  STABLE    (≥  0.0): mild squeeze, buffers intact")
            appendLine("  STRESSED  (≥ −0.5): working-class pinch; credit card debt rising")
            appendLine("  STRAINED  (≥ −1.0): lower income brackets under significant distress")
            appendLine("  BREAKING  (< −1.0): systemic consumer stress; demand collapse risk")
            appendLine()
            appendLine("▌ TASK  (use Google Search grounding for current real-world context)")
            appendLine()
            appendLine("  Search for: current grocery prices, gas prices, rent inflation,")
            appendLine("  credit card delinquency trends, food bank demand, bankruptcy filings,")
            appendLine("  and consumer sentiment in ${"${r.lastDataMonth}"}.")
            appendLine()
            appendLine("  Write a plain-English consumer stress report in exactly 4 paragraphs:")
            appendLine()
            appendLine("  1. PRICE SQUEEZE: Which essentials are most expensive relative to wages?")
            appendLine("     Translate the EIP data into dollar terms — e.g., how many extra")
            appendLine("     hours of work does a Q1 household need to cover their grocery basket?")
            appendLine()
            appendLine("  2. DEBT & SAVINGS: How are households managing the squeeze?")
            appendLine("     Reference the ASI data: are savings depleting, CC balances rising?")
            appendLine("     What does the delinquency trend imply about household runway?")
            appendLine()
            appendLine("  3. LEADING STRESS SIGNALS: What do the FSS indicators and your search")
            appendLine("     results (food bank usage, bankruptcy trends, trade-down behavior)")
            appendLine("     signal about the next 3–6 months?")
            appendLine()
            appendLine("  4. RELIEF OR RISK: What macro factor (Fed rate cuts, wage acceleration,")
            appendLine("     commodity price drop) could most quickly relieve consumer stress?")
            appendLine("     What single event would most likely push PULSE into BREAKING?")
            appendLine()
            appendLine("  Format: Plain text. 4 paragraphs labeled '1.', '2.', '3.', '4.'")
            appendLine("  Tone: Senior economist explaining to an informed public. ~200 words.")
        }.trimEnd()
    }

    private fun regimeShort(v: Float) = when {
        v >= 0.5f  -> "RESILIENT"
        v >= 0.0f  -> "STABLE   "
        v >= -0.5f -> "STRESSED "
        v >= -1.0f -> "STRAINED "
        else       -> "BREAKING "
    }
}
