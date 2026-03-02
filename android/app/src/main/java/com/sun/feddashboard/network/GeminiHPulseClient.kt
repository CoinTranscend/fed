package com.sun.feddashboard.network

import com.sun.feddashboard.domain.HPulseEngine
import com.sun.feddashboard.model.HPulseResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Always-on Gemini narrative for the HPulse Household Stress Index.
 *
 * Called automatically alongside every FRED refresh. Uses Google Search
 * grounding to pull current rent, grocery, gas, utility, and debt trends
 * across income brackets.
 */
object GeminiHPulseClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val MODEL = "gemini-2.5-flash"

    data class GeminiHPulseResponse(
        val text: String?,
        val errorCode: Int? = null,
    )

    fun fetchNarrative(result: HPulseResult, apiKey: String): GeminiHPulseResponse {
        if (apiKey.isBlank()) return GeminiHPulseResponse(null)

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
                if (!resp.isSuccessful) return GeminiHPulseResponse(null, resp.code)
                val root = JSONObject(resp.body?.string().orEmpty())
                val text = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                GeminiHPulseResponse(text)
            }
        } catch (_: Throwable) {
            GeminiHPulseResponse(null)
        }
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    private fun buildPrompt(r: HPulseResult): String {
        val essComp = r.essentialsComponents.joinToString("\n") { c ->
            "  %-22s  score=%5.1f/100  wt=%.2f".format(c.label, c.rawScore, c.weight)
        }
        val debtComp = r.debtComponents.joinToString("\n") { c ->
            "  %-22s  score=%5.1f/100  wt=%.2f".format(c.label, c.rawScore, c.weight)
        }
        val traj = r.points.takeLast(12).joinToString("\n") { pt ->
            "  ${pt.monthLabel.padEnd(8)}  composite=%5.1f  burn=%5.1f  middle=%5.1f  buffer=%5.1f".format(
                pt.composite, pt.burnScore, pt.middleScore, pt.bufferScore)
        }

        return buildString {
            appendLine("╔══════════════════════════════════════════════════════════╗")
            appendLine("  HPulse — Household Pulse Index  (0-100, higher = more stress)")
            appendLine("  Data through: ${r.lastDataMonth}")
            appendLine("╚══════════════════════════════════════════════════════════╝")
            appendLine()
            appendLine("▌ CURRENT TIER SCORES  (0 = no stress, 100 = maximum stress)")
            appendLine("  Composite (all tiers)  : ${"%.1f".format(r.composite)}  →  ${r.band}")
            appendLine("  Burn Zone   (≤ \$65 k)  : ${"%.1f".format(r.burnScore)}   [59% of US households]")
            appendLine("  Middle Pulse (\$65-105k) : ${"%.1f".format(r.middleScore)}")
            appendLine("  Buffer Zone (\$105-175k): ${"%.1f".format(r.bufferScore)}")
            appendLine()
            appendLine("  Band definitions:")
            appendLine("  0–24  STABLE   — essentials affordable relative to wages")
            appendLine("  25–49 WARMING  — mild stress; limited disposable slack")
            appendLine("  50–74 STRAINED — significant squeeze; debt rising")
            appendLine("  75–100 BURN    — crisis-level household stress")
            appendLine()
            appendLine("▌ ESSENTIALS PRESSURE COMPONENTS")
            appendLine("  (Real burden = price YoY − wage YoY; score anchored to historical stress)")
            appendLine(essComp)
            appendLine()
            appendLine("▌ DEBT STRESS COMPONENTS")
            appendLine(debtComp)
            appendLine()
            appendLine("▌ 12-MONTH HISTORY")
            appendLine(traj)
            appendLine()
            appendLine("▌ TASK  (use Google Search grounding for current real-world context)")
            appendLine()
            appendLine("  Search for: current US rent prices, grocery inflation by category,")
            appendLine("  gas prices, utility bills, credit card delinquency trends,")
            appendLine("  and household stress signals in ${r.lastDataMonth}.")
            appendLine()
            appendLine("  Write a plain-English 4-paragraph household stress report:")
            appendLine()
            appendLine("  1. ESSENTIALS SQUEEZE: Which household costs hit low-income families")
            appendLine("     hardest right now? Give dollar-level context (e.g., median rent,")
            appendLine("     weekly grocery basket cost) and how wages compare.")
            appendLine()
            appendLine("  2. DEBT & BUFFERS: How are households coping?")
            appendLine("     Reference the debt stress data: are CC balances rising,")
            appendLine("     savings depleted? What does delinquency imply about runway?")
            appendLine()
            appendLine("  3. INCOME TIER DIVERGENCE: Where is the gap between Burn Zone")
            appendLine("     vs Buffer Zone households widening or narrowing? Which income")
            appendLine("     bracket is most at risk over the next 6 months?")
            appendLine()
            appendLine("  4. OUTLOOK: What single macro factor (rate cut, wage acceleration,")
            appendLine("     energy price drop) would most quickly relieve household stress?")
            appendLine("     What would push the Burn Zone score above 75?")
            appendLine()
            appendLine("  Format: Plain text. 4 paragraphs labeled '1.', '2.', '3.', '4.'")
            appendLine("  Tone: Senior economist, accessible to general public. ~200 words.")
        }.trimEnd()
    }
}
