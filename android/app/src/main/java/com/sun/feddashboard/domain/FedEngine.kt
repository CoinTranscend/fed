package com.sun.feddashboard.domain

import com.sun.feddashboard.model.ChartPoint
import com.sun.feddashboard.model.ComponentReading
import com.sun.feddashboard.model.RegularResult
import com.sun.feddashboard.network.FredClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Computes the two "regular" (coincident/lagging) composites that the Fed actually watches:
 *
 *  ISI — Inflation Strength Index (10 equal-weight series)
 *  LSI — Labor Strength Index     (9 equal-weight series)
 *
 * Same pipeline as the leading engines but equal-weight (weight = 1/n).
 * ISI regimes: ELEVATED ≥ 0.5 | RISING ≥ 0.0 | ANCHORED ≥ -0.5 | COOLING ≥ -1.0 | DEFLATIONARY < -1.0
 * LSI regimes: STRONG ≥ 0.5   | MODERATE ≥ 0.0 | SOFTENING ≥ -0.5 | WEAK ≥ -1.0  | CRITICAL < -1.0
 */
object FedEngine {

    private data class SeriesSpec(
        val id: String,
        val label: String,
        val yoy: Boolean,
        val invert: Boolean = false,
    )

    // ISI: what the Fed monitors for inflation (Core CPI, PCE, market expectations, etc.)
    private val ISI_SERIES = listOf(
        SeriesSpec("CPILFESL",              "Core CPI",              yoy = true),
        SeriesSpec("PCEPILFE",              "Core PCE",              yoy = true),
        SeriesSpec("PCETRIM12M159SFRBDAL",  "Trimmed PCE (Dallas)",  yoy = false),
        SeriesSpec("CORESTICKM159SFRBATL",  "Sticky CPI (Atlanta)",  yoy = false),
        SeriesSpec("PPIFIS",                "PPI Final Demand",      yoy = true),
        SeriesSpec("PPIACO",                "PPI All Commodities",   yoy = true),
        SeriesSpec("T5YIE",                 "5Y Breakeven",          yoy = false),
        SeriesSpec("T10YIE",                "10Y Breakeven",         yoy = false),
        SeriesSpec("CUSR0000SAH1",          "Shelter CPI",           yoy = true),
        SeriesSpec("MICH",                  "UMich Inflation Exp.",  yoy = false),
    )

    // LSI: what the Fed monitors for employment (LSAP mandate)
    private val LSI_SERIES = listOf(
        SeriesSpec("PAYEMS",        "Nonfarm Payrolls",     yoy = true),
        SeriesSpec("UNRATE",        "Unemployment Rate",    yoy = false, invert = true),
        SeriesSpec("CIVPART",       "Labor Force Partic.",  yoy = false),
        SeriesSpec("ICSA",          "Initial Claims",       yoy = false, invert = true),
        SeriesSpec("CCSA",          "Continuing Claims",    yoy = false, invert = true),
        SeriesSpec("JTSJOL",        "Job Openings (JOLTS)", yoy = true),
        SeriesSpec("JTSQUR",        "Quits Rate",           yoy = false),
        SeriesSpec("CES0500000003", "Avg Hourly Earnings",  yoy = true),
        SeriesSpec("MANEMP",        "Mfg Employment",       yoy = true),
    )

    data class FedResults(
        val isi: RegularResult?,
        val lsi: RegularResult?,
    )

    fun compute(fredApiKey: String): FedResults {
        val isi = computeIndex("ISI", ISI_SERIES, fredApiKey, ::classifyInflation)
        val lsi = computeIndex("LSI", LSI_SERIES, fredApiKey, ::classifyLabor)
        return FedResults(isi, lsi)
    }

    private fun computeIndex(
        name: String,
        seriesList: List<SeriesSpec>,
        fredApiKey: String,
        classify: (Double) -> String,
    ): RegularResult? {
        if (fredApiKey.isBlank()) return null

        val startDate = LocalDate.now().minusYears(7)
            .format(DateTimeFormatter.ISO_LOCAL_DATE)

        val rawSeries = mutableMapOf<String, List<Pair<String, Double?>>>()
        for (spec in seriesList) {
            val data = try {
                FredClient.fetchSeries(spec.id, fredApiKey, startDate)
            } catch (_: Exception) { emptyList() }
            if (data.isNotEmpty()) rawSeries[spec.id] = data
        }
        if (rawSeries.isEmpty()) return null

        val allMonths = rawSeries.values
            .flatMap { s -> s.map { it.first } }
            .toSortedSet().toList()

        // Equal weight: 1 / n_available
        val n = seriesList.size.toDouble()
        val equalWeight = 1.0 / n

        val signedZScores = mutableMapOf<String, Map<String, Double?>>()
        val rawZScores    = mutableMapOf<String, Map<String, Double?>>()

        for (spec in seriesList) {
            val raw = rawSeries[spec.id] ?: continue
            val rawMap = raw.toMap()
            val filled    = EngineBase.forwardFill(allMonths, rawMap)
            val processed = if (spec.yoy) EngineBase.applyYoY(allMonths, filled) else filled
            val zScores   = EngineBase.rollingZScore(allMonths, processed)
            rawZScores[spec.id] = zScores
            val sign = if (spec.invert) -1.0 else 1.0
            signedZScores[spec.id] = zScores.mapValues { (_, v) -> v?.let { it * sign } }
        }

        // Equal-weight composite: mean of available signed z-scores at each month
        val composite = mutableMapOf<String, Double>()
        for (month in allMonths) {
            val vals = signedZScores.values.mapNotNull { it[month] }.filter { !it.isNaN() }
            if (vals.isNotEmpty()) composite[month] = vals.average()
        }
        if (composite.isEmpty()) return null

        val sortedMonths = composite.keys.sorted()
        val points = sortedMonths.takeLast(48).map { m ->
            ChartPoint(EngineBase.formatMonthLabel(m), composite[m]!!.toFloat())
        }

        val lastMonth = sortedMonths.last()
        val current   = composite[lastMonth]!!

        val components = seriesList.mapNotNull { spec ->
            val zRaw = rawZScores[spec.id]?.get(lastMonth) ?: return@mapNotNull null
            val sign = if (spec.invert) -1.0 else 1.0
            ComponentReading(
                seriesId     = spec.id,
                label        = spec.label,
                zScore       = zRaw,
                weight       = equalWeight,
                contribution = zRaw * sign * equalWeight,
            )
        }

        return RegularResult(
            points        = points,
            current       = current.toFloat(),
            regime        = classify(current),
            updatedAt     = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd HH:mm")),
            components    = components,
            lastDataMonth = EngineBase.formatMonthLabel(lastMonth),
        )
    }

    private fun classifyInflation(score: Double) = when {
        score >= 0.5  -> "ELEVATED"
        score >= 0.0  -> "RISING"
        score >= -0.5 -> "ANCHORED"
        score >= -1.0 -> "COOLING"
        else          -> "DEFLATIONARY"
    }

    private fun classifyLabor(score: Double) = when {
        score >= 0.5  -> "STRONG"
        score >= 0.0  -> "MODERATE"
        score >= -0.5 -> "SOFTENING"
        score >= -1.0 -> "WEAK"
        else          -> "CRITICAL"
    }
}
