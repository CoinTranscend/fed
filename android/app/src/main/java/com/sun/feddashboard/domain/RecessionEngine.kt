package com.sun.feddashboard.domain

import com.sun.feddashboard.model.ChartPoint
import com.sun.feddashboard.model.ComponentReading
import com.sun.feddashboard.model.LeadingResult
import com.sun.feddashboard.network.FredClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Recession Risk Index (RRI) — a forward-looking composite designed to
 * predict recession 6–18 months ahead using pure FRED data.
 *
 * Series rationale:
 *  T10Y2Y  — Yield curve (10Y−2Y): inversion is the single best recession predictor
 *  T10Y3M  — Yield curve (10Y−3M): confirms inversion signal
 *  BAMLH0A0HYM2 — HY credit spread: tightening = risk-on, widening = recession risk
 *  PERMIT  — Building permits YoY: housing leads the economy by ~6–12 months
 *  ICSA    — Initial claims YoY: early signal of labor stress
 *  UMCSENT — UMich consumer sentiment: consumer confidence leads spending
 *
 * Regimes: LOW RISK ≥ 0.5 | STABLE ≥ 0.0 | CAUTION ≥ -0.5 | WARNING ≥ -1.0 | CRITICAL < -1.0
 */
object RecessionEngine {

    private data class SeriesSpec(
        val id: String,
        val label: String,
        val weight: Double,
        val yoy: Boolean,
        val invert: Boolean,
    )

    private val SERIES = listOf(
        // Yield curve — most powerful leading signal, no YoY (already a spread)
        SeriesSpec("T10Y2Y",         "10Y−2Y Yield Spread",   0.25, yoy = false, invert = true),
        SeriesSpec("T10Y3M",         "10Y−3M Yield Spread",   0.20, yoy = false, invert = true),
        // Credit stress
        SeriesSpec("BAMLH0A0HYM2",   "HY Credit Spread",      0.20, yoy = false, invert = true),
        // Real economy leading signals
        SeriesSpec("PERMIT",         "Building Permits",      0.15, yoy = true,  invert = false),
        SeriesSpec("ICSA",           "Initial Claims",        0.10, yoy = true,  invert = true),
        SeriesSpec("UMCSENT",        "Consumer Sentiment",    0.10, yoy = false, invert = false),
    )

    fun compute(fredApiKey: String): LeadingResult? {
        if (fredApiKey.isBlank()) return null

        val startDate = LocalDate.now().minusYears(8)
            .format(DateTimeFormatter.ISO_LOCAL_DATE)

        val rawSeries = mutableMapOf<String, List<Pair<String, Double?>>>()
        for (spec in SERIES) {
            val data = try {
                FredClient.fetchSeries(spec.id, fredApiKey, startDate)
            } catch (_: Exception) { emptyList() }
            if (data.isNotEmpty()) rawSeries[spec.id] = data
        }
        if (rawSeries.isEmpty()) return null

        val allMonths = rawSeries.values
            .flatMap { s -> s.map { it.first } }
            .toSortedSet().toList()

        val weightedZScores = mutableMapOf<String, Map<String, Double?>>()
        val rawZScores      = mutableMapOf<String, Map<String, Double?>>()

        for (spec in SERIES) {
            val raw = rawSeries[spec.id] ?: continue
            val filled    = EngineBase.forwardFill(allMonths, raw.toMap())
            val processed = if (spec.yoy) EngineBase.applyYoY(allMonths, filled) else filled
            val zScores   = EngineBase.rollingZScore(allMonths, processed)
            rawZScores[spec.id] = zScores
            val sign = if (spec.invert) -1.0 else 1.0
            weightedZScores[spec.id] = zScores.mapValues { (_, v) -> v?.let { it * sign * spec.weight } }
        }

        val weightBySeries = SERIES.associate { it.id to it.weight }
        val composite = mutableMapOf<String, Double>()
        for (month in allMonths) {
            var num = 0.0; var den = 0.0
            for ((sid, wz) in weightedZScores) {
                val v = wz[month]
                if (v != null && !v.isNaN()) { num += v; den += weightBySeries[sid] ?: 0.0 }
            }
            if (den > 0.0) composite[month] = num / den
        }
        if (composite.isEmpty()) return null

        val sorted   = composite.keys.sorted()
        val last12   = sorted.takeLast(12)
        val points   = last12.map { m -> ChartPoint(EngineBase.formatMonthLabel(m), composite[m]!!.toFloat()) }
        val lastMonth = sorted.last()
        val current  = composite[lastMonth]!!

        val components = SERIES.mapNotNull { spec ->
            val z    = rawZScores[spec.id]?.get(lastMonth) ?: return@mapNotNull null
            val sign = if (spec.invert) -1.0 else 1.0
            ComponentReading(spec.id, spec.label, z, spec.weight, z * sign * spec.weight)
        }

        return LeadingResult(
            points        = points,
            current       = current.toFloat(),
            regime        = classifyRegime(current),
            updatedAt     = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd HH:mm")),
            components    = components,
            lastDataMonth = EngineBase.formatMonthLabel(lastMonth),
        )
    }

    private fun classifyRegime(score: Double) = when {
        score >= 0.5  -> "LOW RISK"
        score >= 0.0  -> "STABLE"
        score >= -0.5 -> "CAUTION"
        score >= -1.0 -> "WARNING"
        else          -> "CRITICAL"
    }
}
