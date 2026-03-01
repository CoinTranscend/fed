package com.sun.feddashboard.network

import com.sun.feddashboard.model.ComponentReading
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Calls Gemini 2.5 Flash with Google Search grounding to produce a
 * recession risk narrative based on the current RRI data.
 *
 * Pattern copied from option_android's GeminiClient (read-only reference).
 */
object GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private const val MODEL = "gemini-2.5-flash"

    fun fetchRecessionNarrative(
        rriScore: Float,
        rriRegime: String,
        components: List<ComponentReading>,
        apiKey: String,
    ): String? {
        if (apiKey.isBlank()) return null

        val compSummary = components.joinToString("\n") { c ->
            "  ${c.label}: z=${"%+.2f".format(c.zScore)}, contrib=${"%+.3f".format(c.contribution)}"
        }

        val prompt = """
            You are a macroeconomic analyst. Based on the current Recession Risk Index (RRI) data below,
            provide a concise 3-4 sentence recession risk assessment. Search for the latest economic data
            to add context. Focus on what is driving risk higher or lower right now.

            RRI Score: ${"%.2f".format(rriScore)} | Regime: $rriRegime
            Component breakdown:
            $compSummary

            Return ONLY a plain text paragraph (no JSON, no markdown headers, no bullet points).
            Keep it under 100 words, professional, forward-looking.
        """.trimIndent()

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
}
