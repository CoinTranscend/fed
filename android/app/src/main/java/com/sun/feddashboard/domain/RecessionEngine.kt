package com.sun.feddashboard.domain

import com.sun.feddashboard.model.ChartPoint
import com.sun.feddashboard.model.ComponentReading
import com.sun.feddashboard.model.LeadingResult
import com.sun.feddashboard.network.FredClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Recession Risk Index (RRI) — 8-series leading composite designed to predict
 * recession 6–18 months ahead using pure FRED data plus two computed ratios.
 *
 * Weights (sum = 1.0):
 *   T10Y2Y        20%  inv  — yield curve: single best predictor
 *   T10Y3M        15%  inv  — confirms inversion signal
 *   BAMLH0A0HYM2  15%  inv  — HY credit spread: financial stress
 *   PERMIT        15%  YoY  — building permits: housing leads economy 6–12m
 *   ICSA          10%  YoY inv — initial claims: early labor stress
 *   UMCSENT       10%       — consumer confidence leads spending
 *   Copper/Gold   10%       — cross-asset growth proxy (PCOPPUSDM/GOLDAMGBD228NLBM)
 *   Real M2        5%  YoY  — real money supply growth (M2SL/CPIAUCSL)
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

    private val STANDARD_SERIES = listOf(
        SeriesSpec("T10Y2Y",         "10Y−2Y Yield Spread",   0.20, yoy = false, invert = true),
        SeriesSpec("T10Y3M",         "10Y−3M Yield Spread",   0.15, yoy = false, invert = true),
        SeriesSpec("BAMLH0A0HYM2",   "HY Credit Spread",      0.15, yoy = false, invert = true),
        SeriesSpec("PERMIT",         "Building Permits",      0.15, yoy = true,  invert = false),
        SeriesSpec("ICSA",           "Initial Claims",        0.10, yoy = true,  invert = true),
        SeriesSpec("UMCSENT",        "Consumer Sentiment",    0.10, yoy = false, invert = false),
    )

    // Computed: PCOPPUSDM / GOLDAMGBD228NLBM
    // High ratio = industrial demand > safe haven demand = growth signal
    private val COPPER_GOLD = SeriesSpec("COPPER_GOLD", "Copper/Gold Ratio",  0.10, yoy = false, invert = false)

    // Computed: M2SL / CPIAUCSL (YoY applied via spec)
    // Positive real M2 growth = healthy money supply conditions
    private val REAL_M2     = SeriesSpec("REAL_M2",     "Real M2 Growth",     0.05, yoy = true,  invert = false)

    private val ALL_SPECS = STANDARD_SERIES + listOf(COPPER_GOLD, REAL_M2)

    fun compute(fredApiKey: String): LeadingResult? {
        if (fredApiKey.isBlank()) return null

        val startDate = LocalDate.now().minusYears(8)
            .format(DateTimeFormatter.ISO_LOCAL_DATE)

        val rawSeries = mutableMapOf<String, List<Pair<String, Double?>>>()

        // Standard FRED series
        for (spec in STANDARD_SERIES) {
            val data = try {
                FredClient.fetchSeries(spec.id, fredApiKey, startDate)
            } catch (_: Exception) { emptyList() }
            if (data.isNotEmpty()) rawSeries[spec.id] = data
        }

        // Copper/Gold ratio: PCOPPUSDM / GOLDAMGBD228NLBM
        try {
            val copper = FredClient.fetchSeries("PCOPPUSDM",        fredApiKey, startDate)
            val gold   = FredClient.fetchSeries("GOLDAMGBD228NLBM", fredApiKey, startDate)
            val ratio  = alignedRatio(copper, gold)
            if (ratio.isNotEmpty()) rawSeries[COPPER_GOLD.id] = ratio
        } catch (_: Exception) { }

        // Real M2: M2SL / CPIAUCSL (YoY applied later via spec.yoy = true)
        try {
            val m2  = FredClient.fetchSeries("M2SL",      fredApiKey, startDate)
            val cpi = FredClient.fetchSeries("CPIAUCSL",  fredApiKey, startDate)
            val rm2 = alignedRatio(m2, cpi)
            if (rm2.isNotEmpty()) rawSeries[REAL_M2.id] = rm2
        } catch (_: Exception) { }

        if (rawSeries.isEmpty()) return null

        val allMonths = rawSeries.values
            .flatMap { s -> s.map { it.first } }
            .toSortedSet().toList()

        val weightedZScores = mutableMapOf<String, Map<String, Double?>>()
        val rawZScores      = mutableMapOf<String, Map<String, Double?>>()

        for (spec in ALL_SPECS) {
            val raw = rawSeries[spec.id] ?: continue
            val filled    = EngineBase.forwardFill(allMonths, raw.toMap())
            val processed = if (spec.yoy) EngineBase.applyYoY(allMonths, filled) else filled
            val zScores   = EngineBase.rollingZScore(allMonths, processed)
            rawZScores[spec.id] = zScores
            val sign = if (spec.invert) -1.0 else 1.0
            weightedZScores[spec.id] = zScores.mapValues { (_, v) -> v?.let { it * sign * spec.weight } }
        }

        val weightBySeries = ALL_SPECS.associate { it.id to it.weight }
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

        val sorted    = composite.keys.sorted()
        val points    = sorted.takeLast(48).map { m ->
            ChartPoint(EngineBase.formatMonthLabel(m), composite[m]!!.toFloat())
        }
        val lastMonth = sorted.last()
        val current   = composite[lastMonth]!!

        val components = ALL_SPECS.mapNotNull { spec ->
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

    /** Aligns two date-value lists by date and divides numerator / denominator. */
    private fun alignedRatio(
        numerator: List<Pair<String, Double?>>,
        denominator: List<Pair<String, Double?>>,
    ): List<Pair<String, Double?>> {
        val denMap = denominator.toMap()
        return numerator.map { (date, num) ->
            val den = denMap[date]
            date to if (num != null && den != null && den != 0.0) num / den else null
        }
    }

    private fun classifyRegime(score: Double) = when {
        score >= 0.5  -> "LOW RISK"
        score >= 0.0  -> "STABLE"
        score >= -0.5 -> "CAUTION"
        score >= -1.0 -> "WARNING"
        else          -> "CRITICAL"
    }
}
