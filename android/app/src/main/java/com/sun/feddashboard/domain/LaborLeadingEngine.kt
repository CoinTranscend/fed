package com.sun.feddashboard.domain

import com.sun.feddashboard.model.ChartPoint
import com.sun.feddashboard.model.ComponentReading
import com.sun.feddashboard.model.LeadingResult
import com.sun.feddashboard.network.FredClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Leading Labor Market Strength Index (LLMSI).
 *
 * 7 FRED series with weights → YoY (where flagged) → 60-month rolling z-score →
 * sign-flip for inverted series → renormalized weighted mean.
 *
 * Regimes: STRONG ≥ 0.5 | MODERATE ≥ 0.0 | SOFTENING ≥ -0.5 | WEAK ≥ -1.0 | CRITICAL < -1.0
 */
object LaborLeadingEngine {

    private data class SeriesSpec(
        val id: String,
        val label: String,
        val weight: Double,
        val yoy: Boolean,
        val invert: Boolean,
    )

    private val SERIES = listOf(
        SeriesSpec("ICSA",      "Initial Claims",       0.20, yoy = true,  invert = true),
        SeriesSpec("AWHMAN",    "Mfg Avg Weekly Hours", 0.15, yoy = false, invert = false),
        SeriesSpec("TEMPHELPS", "Temp Help Services",   0.15, yoy = true,  invert = false),
        SeriesSpec("JTSJOL",    "Job Openings (JOLTS)", 0.15, yoy = true,  invert = false),
        SeriesSpec("JTSLDL",    "Layoffs (JOLTS)",      0.15, yoy = true,  invert = true),
        SeriesSpec("JTSQUR",    "Quits Rate",           0.10, yoy = false, invert = false),
        SeriesSpec("CCSA",      "Continuing Claims",    0.10, yoy = true,  invert = true),
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
            val sign = if (spec.invert) -1.0 else 1.0
            val weighted = zScores.mapValues { (_, v) -> v?.let { it * sign * spec.weight } }
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
            val z    = rawZScores[spec.id]?.get(lastMonth) ?: return@mapNotNull null
            val sign = if (spec.invert) -1.0 else 1.0
            ComponentReading(
                seriesId     = spec.id,
                label        = spec.label,
                zScore       = z,
                weight       = spec.weight,
                contribution = z * sign * spec.weight,
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

    private fun classifyRegime(score: Double) = when {
        score >= 0.5  -> "STRONG"
        score >= 0.0  -> "MODERATE"
        score >= -0.5 -> "SOFTENING"
        score >= -1.0 -> "WEAK"
        else          -> "CRITICAL"
    }
}
