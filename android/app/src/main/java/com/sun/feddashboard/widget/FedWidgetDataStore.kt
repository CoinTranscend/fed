package com.sun.feddashboard.widget

import android.content.Context
import com.google.gson.Gson
import com.sun.feddashboard.model.LeadingResult
import com.sun.feddashboard.model.RegularResult

/**
 * Thin SharedPreferences wrapper for the Fed widget.
 *
 * Strategy:
 *   - Widget-specific cache lives in "fed_widget_prefs".
 *   - On load, falls back to the main app's "fed_prefs" cache so the widget
 *     shows data immediately if the user has already refreshed the main app.
 *   - FRED API key is read from "fed_prefs" (where the user saved it in Settings).
 */
object FedWidgetDataStore {

    private const val WIDGET_PREFS = "fed_widget_prefs"
    private const val APP_PREFS    = "fed_prefs"

    // ── Keys ─────────────────────────────────────────────────────────────────

    private const val KEY_FRED    = "fred_api_key"   // in APP_PREFS
    private const val KEY_LIIMSI  = "w_liimsi"
    private const val KEY_LLMSI   = "w_llmsi"
    private const val KEY_ISI     = "w_isi"
    private const val KEY_LSI     = "w_lsi"
    private const val KEY_RRI     = "w_rri"
    private const val KEY_UPDATED = "w_updated_ms"
    const val PREF_WIDGET_ENABLED = "widget_enabled"

    // Matching keys from MainViewModel
    private const val APP_CACHE_ISI    = "cache_isi"
    private const val APP_CACHE_LSI    = "cache_lsi"
    private const val APP_CACHE_LIIMSI = "cache_liimsi"
    private const val APP_CACHE_LLMSI  = "cache_llmsi"
    private const val APP_CACHE_RRI    = "cache_rri"

    private val gson = Gson()

    // ── Public API ────────────────────────────────────────────────────────────

    fun getFredKey(ctx: Context): String =
        ctx.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FRED, "") ?: ""

    data class WidgetData(
        val liimsi   : LeadingResult?,
        val llmsi    : LeadingResult?,
        val isi      : RegularResult?,
        val lsi      : RegularResult?,
        val rri      : LeadingResult?,
        val updatedMs: Long,
    )

    /** Only writes non-null results — never stores "null" strings that would
     *  block the fallback to the main app cache on the next load(). */
    fun save(ctx: Context, data: WidgetData) {
        val ed = ctx.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE).edit()
        data.liimsi?.let { ed.putString(KEY_LIIMSI, gson.toJson(it)) }
        data.llmsi?.let  { ed.putString(KEY_LLMSI,  gson.toJson(it)) }
        data.isi?.let    { ed.putString(KEY_ISI,    gson.toJson(it)) }
        data.lsi?.let    { ed.putString(KEY_LSI,    gson.toJson(it)) }
        data.rri?.let    { ed.putString(KEY_RRI,    gson.toJson(it)) }
        ed.putLong(KEY_UPDATED, data.updatedMs).apply()
    }

    fun load(ctx: Context): WidgetData {
        val wp = ctx.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
        val ap = ctx.getSharedPreferences(APP_PREFS,    Context.MODE_PRIVATE)

        // "null" strings written by old buggy code — treat as absent so we fall back to app cache
        fun String?.realOrNull() = if (this == null || this == "null") null else this

        // Prefer widget-specific cache; fall back to main app cache
        fun <T> readJson(wKey: String, aKey: String, type: Class<T>): T? {
            val json = wp.getString(wKey, null).realOrNull() ?: ap.getString(aKey, null).realOrNull()
            return json?.let { runCatching { gson.fromJson(it, type) }.getOrNull() }
        }

        return WidgetData(
            liimsi    = readJson(KEY_LIIMSI, APP_CACHE_LIIMSI, LeadingResult::class.java),
            llmsi     = readJson(KEY_LLMSI,  APP_CACHE_LLMSI,  LeadingResult::class.java),
            isi       = readJson(KEY_ISI,    APP_CACHE_ISI,    RegularResult::class.java),
            lsi       = readJson(KEY_LSI,    APP_CACHE_LSI,    RegularResult::class.java),
            rri       = readJson(KEY_RRI,    APP_CACHE_RRI,    LeadingResult::class.java),
            updatedMs = wp.getLong(KEY_UPDATED, 0L),
        )
    }
}
