package com.sun.feddashboard.domain

import com.sun.feddashboard.model.ChartPoint
import com.sun.feddashboard.model.ComponentReading
import com.sun.feddashboard.model.LeadingResult
import com.sun.feddashboard.network.FredClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Leading Inflation Indicators Strength Index (LIIMSI).
 *
 * 7 FRED series with weights → YoY → 60-month rolling z-score →
 * renormalized weighted mean.
 *
 * Regimes: ELEVATED ≥ 0.5 | RISING ≥ 0.0 | ANCHORED ≥ -0.5 | COOLING ≥ -1.0 | DEFLATIONARY < -1.0
 *
 * Corrected series IDs vs the original Android app:
 *   FLEXCPIM159SFRBATL (not FLEXM159SFRBATL)
 *   MICH               (not NFIBPRCE — doesn't exist in FRED)
 */
object InflationLeadingEngine {

    private data class SeriesSpec(
        val id: String,
        val label: String,
        val weight: Double,
        val yoy: Boolean,
    )

    private val SERIES = listOf(
        SeriesSpec("T5YIE",              "5Y TIPS Breakeven",      0.20, yoy = false),
        SeriesSpec("PPIFIS",             "PPI Final Demand",        0.20, yoy = true),
        SeriesSpec("FLEXCPIM159SFRBATL", "ATL Flexible CPI",        0.15, yoy = false),
        SeriesSpec("MICH",               "UMich Infl. Expectation", 0.15, yoy = false),
        SeriesSpec("CES0500000003",      "Avg Hourly Earnings",     0.10, yoy = true),
        SeriesSpec("PPIACO",             "PPI All Commodities",     0.10, yoy = true),
        SeriesSpec("T5YIFR",             "5Y/5Y Fwd Breakeven",     0.10, yoy = false),
    )

    fun compute(fredApiKey: String): LeadingResult? {
        if (fredApiKey.isBlank()) return null

        val startDate = LocalDate.now().minusYears(7)
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
            val rawMap = raw.toMap()
            val filled    = EngineBase.forwardFill(allMonths, rawMap)
            val processed = if (spec.yoy) EngineBase.applyYoY(allMonths, filled) else filled
            val zScores   = EngineBase.rollingZScore(allMonths, processed)
            rawZScores[spec.id] = zScores
            val weighted = zScores.mapValues { (_, v) -> v?.let { it * spec.weight } }
            weightedZScores[spec.id] = weighted
        }

        val weightBySeries = SERIES.associate { it.id to it.weight }
        val composite = mutableMapOf<String, Double>()
        for (month in allMonths) {
            var numerator = 0.0; var denominator = 0.0
            for ((sid, wzMap) in weightedZScores) {
                val wz = wzMap[month]
                if (wz != null && !wz.isNaN()) {
                    numerator   += wz
                    denominator += weightBySeries[sid] ?: 0.0
                }
            }
            if (denominator > 0.0) composite[month] = numerator / denominator
        }
        if (composite.isEmpty()) return null

        val sortedMonths = composite.keys.sorted()
        val points = sortedMonths.takeLast(48).map { m ->
            ChartPoint(EngineBase.formatMonthLabel(m), composite[m]!!.toFloat())
        }

        val lastMonth = sortedMonths.last()
        val current   = composite[lastMonth]!!

        val components = SERIES.mapNotNull { spec ->
            val z = rawZScores[spec.id]?.get(lastMonth) ?: return@mapNotNull null
            ComponentReading(
                seriesId     = spec.id,
                label        = spec.label,
                zScore       = z,
                weight       = spec.weight,
                contribution = z * spec.weight,
            )
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

    // ── 30-year history ───────────────────────────────────────────────────────

    fun computeHistory(fredApiKey: String, yearsBack: Int = 32): List<Pair<String, Float>>? {
        if (fredApiKey.isBlank()) return null
        val startDate = LocalDate.now().minusYears(yearsBack.toLong())
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
        for (spec in SERIES) {
            val raw = rawSeries[spec.id] ?: continue
            val rawMap    = raw.toMap()
            val filled    = EngineBase.forwardFill(allMonths, rawMap)
            val processed = if (spec.yoy) EngineBase.applyYoY(allMonths, filled) else filled
            val zScores   = EngineBase.rollingZScore(allMonths, processed)
            weightedZScores[spec.id] = zScores.mapValues { (_, v) -> v?.let { it * spec.weight } }
        }

        val weightBySeries = SERIES.associate { it.id to it.weight }
        val composite = mutableMapOf<String, Double>()
        for (month in allMonths) {
            var numerator = 0.0; var denominator = 0.0
            for ((sid, wzMap) in weightedZScores) {
                val wz = wzMap[month]
                if (wz != null && !wz.isNaN()) {
                    numerator   += wz
                    denominator += weightBySeries[sid] ?: 0.0
                }
            }
            if (denominator > 0.0) composite[month] = numerator / denominator
        }
        if (composite.isEmpty()) return null

        return composite.keys.sorted().map { m -> m to composite[m]!!.toFloat() }
    }

    private fun classifyRegime(score: Double) = when {
        score >= 0.5  -> "ELEVATED"
        score >= 0.0  -> "RISING"
        score >= -0.5 -> "ANCHORED"
        score >= -1.0 -> "COOLING"
        else          -> "DEFLATIONARY"
    }
}
