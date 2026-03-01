package com.sun.feddashboard.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal FRED REST client.
 * Mirrors the approach in option_android's FredClient.kt.
 * Must be called from an IO dispatcher.
 */
object FredClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches monthly observations for [seriesId] starting from [startDate] (YYYY-MM-DD).
     * Returns a list of (YYYY-MM, Double?) pairs — null where FRED reports ".".
     * Returns empty list on any network or parse error.
     */
    fun fetchSeries(
        seriesId: String,
        apiKey: String,
        startDate: String,
    ): List<Pair<String, Double?>> {
        val url = "https://api.stlouisfed.org/fred/series/observations" +
            "?series_id=$seriesId" +
            "&api_key=$apiKey" +
            "&observation_start=$startDate" +
            "&frequency=m" +
            "&aggregation_method=avg" +
            "&file_type=json"

        val request = Request.Builder().url(url).build()
        val body = client.newCall(request).execute().use { it.body?.string() } ?: return emptyList()

        val observations = JSONObject(body).optJSONArray("observations") ?: return emptyList()

        val result = mutableListOf<Pair<String, Double?>>()
        for (i in 0 until observations.length()) {
            val obs = observations.getJSONObject(i)
            val dateStr = obs.getString("date")           // "2024-01-01"
            val valueStr = obs.getString("value")         // "123.45" or "."
            val month = dateStr.substring(0, 7)           // "2024-01"
            val value = valueStr.toDoubleOrNull()          // null for "."
            result.add(month to value)
        }
        return result
    }
}
