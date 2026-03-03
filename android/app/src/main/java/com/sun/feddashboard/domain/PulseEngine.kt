package com.sun.feddashboard.domain

import com.sun.feddashboard.model.ChartPoint
import com.sun.feddashboard.model.ComponentReading
import com.sun.feddashboard.model.PulseResult
import com.sun.feddashboard.network.FredClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * PULSE — People's Underlying Living Stress Engine
 *
 * Methodology: Distributional inflation framework (Fed / Hobijn & Lagakos).
 * Higher score = more resilient consumers.  Lower score = more stressed.
 *
 * Composite = 0.40 × EIP + 0.35 × ASI + 0.25 × FSS
 *
 * EIP (Essentials Inflation Pressure)
 *   Real price burden = -(item_YoY − AHE_YoY) per quintile basket.
 *   CEX spending weights by quintile (Q1 = bottom 20 %, Q2 = 20–40 %, Q3 = 40–60 %).
 *   Democratic weighting: 0.50 × Q1 + 0.35 × Q2 + 0.15 × Q3.
 *   Result is 60-month rolling z-scored so positive = wages outpacing prices = good.
 *
 * ASI (Affordability Squeeze Index)
 *   Weighted z-scores of CC debt, delinquency, savings rate, charge-offs.
 *   Stress indicators inverted so positive = healthy household balance sheet.
 *
 * FSS (Forward Stress Signal)
 *   Weighted z-scores of consumer sentiment, food & bev retail, real disposable income.
 *   Positive = consumers still spending and earning ahead of stress.
 *
 * Regimes: RESILIENT ≥ +0.5 | STABLE ≥ 0.0 | STRESSED ≥ −0.5 | STRAINED ≥ −1.0 | BREAKING < −1.0
 */
object PulseEngine {

    // ── EIP series specs ──────────────────────────────────────────────────────
    // CEX spending shares (Q1/Q2/Q3) from BLS Consumer Expenditure Survey 2022-23.
    // Burden = -(item_YoY − AHE_YoY): positive when wages outpace item prices.

    private data class EipSpec(
        val id: String,
        val label: String,
        val q1: Double,   // Q1 budget share
        val q2: Double,   // Q2 budget share
        val q3: Double,   // Q3 budget share
    )

    private val EIP_SERIES = listOf(
        EipSpec("CUSR0000SEHA",  "Shelter CPI",      0.420, 0.370, 0.320),
        EipSpec("CPIUFDSL",      "Food at Home CPI", 0.100, 0.090, 0.080),
        EipSpec("CUSR0000SETB01","Gasoline CPI",     0.089, 0.100, 0.100),
        EipSpec("APU0000708111", "Eggs (per doz.)",  0.018, 0.012, 0.008),
        EipSpec("APU0000709112", "Chicken (per lb)", 0.021, 0.016, 0.011),
        EipSpec("APU0000710411", "Milk (per gal.)",  0.014, 0.010, 0.007),
    )
    private const val AHE_ID = "CES0500000003"   // Average Hourly Earnings

    // ── ASI series specs ──────────────────────────────────────────────────────
    // invert=true → multiply z-score by −1 so positive z = less stress = good.

    private data class StdSpec(
        val id: String,
        val label: String,
        val weight: Double,
        val yoy: Boolean,
        val invert: Boolean,
    )

    private val ASI_SERIES = listOf(
        StdSpec("DRCCLACBS", "CC Delinquency Rate",   0.30, false, true),   // high = bad → invert
        StdSpec("REVOLSL",   "CC Revolving Balances", 0.25, true,  true),   // high growth = bad → invert
        StdSpec("PSAVERT",   "Personal Savings Rate", 0.25, false, false),  // high = good → keep
        StdSpec("CORCCACBS", "CC Charge-Off Rate",    0.20, false, true),   // high = bad → invert
    )

    // ── FSS series specs ──────────────────────────────────────────────────────

    private val FSS_SERIES = listOf(
        StdSpec("UMCSENT",       "Consumer Sentiment",      0.40, false, false), // high = good
        StdSpec("MRTSSM4451USS", "Food & Bev Retail YoY",  0.30, true,  false), // high growth = good
        StdSpec("DSPIC96",       "Real Disp. Income YoY",  0.30, true,  false), // high growth = good
    )

    // ── Public API ────────────────────────────────────────────────────────────

    fun compute(fredApiKey: String): PulseResult? {
        if (fredApiKey.isBlank()) return null

        val startDate = LocalDate.now().minusYears(7)
            .format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Fetch all series
        val allSpecIds = EIP_SERIES.map { it.id } + listOf(AHE_ID) +
                         ASI_SERIES.map { it.id } + FSS_SERIES.map { it.id }

        val rawSeries = mutableMapOf<String, List<Pair<String, Double?>>>()
        for (id in allSpecIds.distinct()) {
            val data = try { FredClient.fetchSeries(id, fredApiKey, startDate) }
                       catch (_: Exception) { emptyList() }
            if (data.isNotEmpty()) rawSeries[id] = data
        }
        if (rawSeries.isEmpty()) return null

        // Build unified monthly grid
        val allMonths = rawSeries.values
            .flatMap { s -> s.map { it.first } }
            .toSortedSet().toList()

        // ── Compute EIP ───────────────────────────────────────────────────────

        // Forward-fill then YoY for every EIP series and AHE
        val eipYoY  = mutableMapOf<String, Map<String, Double?>>()
        for (spec in EIP_SERIES) {
            val raw = rawSeries[spec.id] ?: continue
            val filled = EngineBase.forwardFill(allMonths, raw.toMap())
            eipYoY[spec.id] = EngineBase.applyYoY(allMonths, filled)
        }
        val aheYoY: Map<String, Double?> = rawSeries[AHE_ID]?.let {
            val filled = EngineBase.forwardFill(allMonths, it.toMap())
            EngineBase.applyYoY(allMonths, filled)
        } ?: emptyMap()

        // Monthly quintile burden (positive = wages outpacing prices = good)
        val q1Raw = LinkedHashMap<String, Double?>()
        val q2Raw = LinkedHashMap<String, Double?>()
        val q3Raw = LinkedHashMap<String, Double?>()
        val eipRaw = LinkedHashMap<String, Double?>()

        for (month in allMonths) {
            val ahe = aheYoY[month]
            var q1n = 0.0; var q1d = 0.0
            var q2n = 0.0; var q2d = 0.0
            var q3n = 0.0; var q3d = 0.0

            for (spec in EIP_SERIES) {
                val item = eipYoY[spec.id]?.get(month)
                if (item != null && ahe != null) {
                    val burden = -(item - ahe)   // positive = wages ahead of prices
                    q1n += spec.q1 * burden; q1d += spec.q1
                    q2n += spec.q2 * burden; q2d += spec.q2
                    q3n += spec.q3 * burden; q3d += spec.q3
                }
            }
            q1Raw[month] = if (q1d > 0.0) q1n / q1d else null
            q2Raw[month] = if (q2d > 0.0) q2n / q2d else null
            q3Raw[month] = if (q3d > 0.0) q3n / q3d else null

            // Democratic weighting across quintiles
            var num = 0.0; var den = 0.0
            listOf(q1Raw[month] to 0.50, q2Raw[month] to 0.35, q3Raw[month] to 0.15)
                .forEach { (v, w) -> if (v != null) { num += v * w; den += w } }
            eipRaw[month] = if (den > 0.0) num / den else null
        }

        val eipZ = EngineBase.rollingZScore(allMonths, eipRaw)

        // EIP component z-scores: each item's own burden z-score (for display)
        val eipBurdenZ = mutableMapOf<String, Map<String, Double?>>()
        for (spec in EIP_SERIES) {
            val burdenSeries = LinkedHashMap<String, Double?>()
            for (month in allMonths) {
                val item = eipYoY[spec.id]?.get(month)
                val ahe  = aheYoY[month]
                burdenSeries[month] = if (item != null && ahe != null) -(item - ahe) else null
            }
            eipBurdenZ[spec.id] = EngineBase.rollingZScore(allMonths, burdenSeries)
        }

        // ── Compute ASI ───────────────────────────────────────────────────────

        val asiZ = computeStdIndex(allMonths, rawSeries, ASI_SERIES)

        // ── Compute FSS ───────────────────────────────────────────────────────

        val fssZ = computeStdIndex(allMonths, rawSeries, FSS_SERIES)

        // ── Composite PULSE ───────────────────────────────────────────────────

        val pulse = LinkedHashMap<String, Double>()
        for (month in allMonths) {
            var num = 0.0; var den = 0.0
            listOf(eipZ[month] to 0.40, asiZ[month] to 0.35, fssZ[month] to 0.25)
                .forEach { (v, w) -> if (v != null && !v.isNaN()) { num += v * w; den += w } }
            if (den > 0.0) pulse[month] = num / den
        }
        if (pulse.isEmpty()) return null

        val sortedMonths = pulse.keys.sorted()
        val lastMonth    = sortedMonths.last()
        val current      = pulse[lastMonth]!!

        val points = sortedMonths.takeLast(48).map { m ->
            ChartPoint(EngineBase.formatMonthLabel(m), pulse[m]!!.toFloat())
        }

        // ── Per-series component readings ─────────────────────────────────────

        val eipComponents = buildEipComponents(eipBurdenZ, lastMonth)
        val asiComponents = buildStdComponents(allMonths, rawSeries, ASI_SERIES, lastMonth)
        val fssComponents = buildStdComponents(allMonths, rawSeries, FSS_SERIES, lastMonth)

        return PulseResult(
            points          = points,
            current         = current.toFloat(),
            regime          = classifyRegime(current),
            updatedAt       = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd HH:mm")),
            lastDataMonth   = EngineBase.formatMonthLabel(lastMonth),
            eipScore        = (eipZ[lastMonth] ?: 0.0).toFloat(),
            asiScore        = (asiZ[lastMonth] ?: 0.0).toFloat(),
            fssScore        = (fssZ[lastMonth] ?: 0.0).toFloat(),
            q1Burden        = (q1Raw[lastMonth] ?: 0.0).toFloat(),
            q2Burden        = (q2Raw[lastMonth] ?: 0.0).toFloat(),
            q3Burden        = (q3Raw[lastMonth] ?: 0.0).toFloat(),
            eipComponents   = eipComponents,
            asiComponents   = asiComponents,
            fssComponents   = fssComponents,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Compute a renormalized weighted composite of rolling z-scores
     * for a list of StdSpec series (ASI or FSS style).
     * invert=true series are multiplied by −1 so positive = healthy.
     */
    private fun computeStdIndex(
        allMonths: List<String>,
        rawSeries: Map<String, List<Pair<String, Double?>>>,
        specs: List<StdSpec>,
    ): Map<String, Double?> {
        val zMaps = mutableMapOf<String, Map<String, Double?>>()
        for (spec in specs) {
            val raw = rawSeries[spec.id] ?: continue
            val filled    = EngineBase.forwardFill(allMonths, raw.toMap())
            val processed = if (spec.yoy) EngineBase.applyYoY(allMonths, filled) else filled
            val z         = EngineBase.rollingZScore(allMonths, processed)
            zMaps[spec.id] = z
        }
        val result = LinkedHashMap<String, Double?>()
        for (month in allMonths) {
            var num = 0.0; var den = 0.0
            for (spec in specs) {
                val z = zMaps[spec.id]?.get(month) ?: continue
                val sign = if (spec.invert) -1.0 else 1.0
                num += sign * z * spec.weight
                den += spec.weight
            }
            result[month] = if (den > 0.0) num / den else null
        }
        return result
    }

    private fun buildEipComponents(
        eipBurdenZ: Map<String, Map<String, Double?>>,
        lastMonth: String,
    ): List<ComponentReading> = EIP_SERIES.mapNotNull { spec ->
        val z = eipBurdenZ[spec.id]?.get(lastMonth) ?: return@mapNotNull null
        ComponentReading(
            seriesId    = spec.id,
            label       = spec.label,
            zScore      = z,
            weight      = spec.q1,                  // show Q1 budget share as weight
            contribution = z * spec.q1,
        )
    }

    private fun buildStdComponents(
        allMonths: List<String>,
        rawSeries: Map<String, List<Pair<String, Double?>>>,
        specs: List<StdSpec>,
        lastMonth: String,
    ): List<ComponentReading> {
        val result = mutableListOf<ComponentReading>()
        for (spec in specs) {
            val raw = rawSeries[spec.id] ?: continue
            val filled    = EngineBase.forwardFill(allMonths, raw.toMap())
            val processed = if (spec.yoy) EngineBase.applyYoY(allMonths, filled) else filled
            val z         = EngineBase.rollingZScore(allMonths, processed)
            val zVal = z[lastMonth] ?: continue
            val sign = if (spec.invert) -1.0 else 1.0
            result.add(ComponentReading(
                seriesId    = spec.id,
                label       = spec.label,
                zScore      = zVal,
                weight      = spec.weight,
                contribution = sign * zVal * spec.weight,
            ))
        }
        return result
    }

    // ── 30-year history export ────────────────────────────────────────────────

    /**
     * Fetches [historyYears] of FRED data and returns the full PULSE composite
     * history as (YYYY-MM, composite z-score) pairs for chart export.
     * This is a separate call from [compute] to avoid bloating the SharedPreferences cache.
     */
    fun computeHistory(fredApiKey: String, historyYears: Long = 30): List<Pair<String, Float>>? {
        if (fredApiKey.isBlank()) return null

        val startDate = LocalDate.now().minusYears(historyYears + 1)  // +1 for YoY warm-up
            .format(DateTimeFormatter.ISO_LOCAL_DATE)

        val allSpecIds = EIP_SERIES.map { it.id } + listOf(AHE_ID) +
                         ASI_SERIES.map { it.id } + FSS_SERIES.map { it.id }

        val rawSeries = mutableMapOf<String, List<Pair<String, Double?>>>()
        for (id in allSpecIds.distinct()) {
            val data = try { FredClient.fetchSeries(id, fredApiKey, startDate) }
                       catch (_: Exception) { emptyList() }
            if (data.isNotEmpty()) rawSeries[id] = data
        }
        if (rawSeries.isEmpty()) return null

        val allMonths = rawSeries.values
            .flatMap { s -> s.map { it.first } }
            .toSortedSet().toList()

        // EIP
        val eipYoY = mutableMapOf<String, Map<String, Double?>>()
        for (spec in EIP_SERIES) {
            val raw = rawSeries[spec.id] ?: continue
            val filled = EngineBase.forwardFill(allMonths, raw.toMap())
            eipYoY[spec.id] = EngineBase.applyYoY(allMonths, filled)
        }
        val aheYoY: Map<String, Double?> = rawSeries[AHE_ID]?.let {
            val filled = EngineBase.forwardFill(allMonths, it.toMap())
            EngineBase.applyYoY(allMonths, filled)
        } ?: emptyMap()

        val eipRaw = LinkedHashMap<String, Double?>()
        for (month in allMonths) {
            val ahe = aheYoY[month]
            var q1n = 0.0; var q1d = 0.0
            var q2n = 0.0; var q2d = 0.0
            var q3n = 0.0; var q3d = 0.0
            for (spec in EIP_SERIES) {
                val item = eipYoY[spec.id]?.get(month)
                if (item != null && ahe != null) {
                    val burden = -(item - ahe)
                    q1n += spec.q1 * burden; q1d += spec.q1
                    q2n += spec.q2 * burden; q2d += spec.q2
                    q3n += spec.q3 * burden; q3d += spec.q3
                }
            }
            val q1 = if (q1d > 0.0) q1n / q1d else null
            val q2 = if (q2d > 0.0) q2n / q2d else null
            val q3 = if (q3d > 0.0) q3n / q3d else null
            var num = 0.0; var den = 0.0
            listOf(q1 to 0.50, q2 to 0.35, q3 to 0.15)
                .forEach { (v, w) -> if (v != null) { num += v * w; den += w } }
            eipRaw[month] = if (den > 0.0) num / den else null
        }
        val eipZ = EngineBase.rollingZScore(allMonths, eipRaw)

        // ASI + FSS
        val asiZ = computeStdIndex(allMonths, rawSeries, ASI_SERIES)
        val fssZ = computeStdIndex(allMonths, rawSeries, FSS_SERIES)

        // Composite
        val result = mutableListOf<Pair<String, Float>>()
        for (month in allMonths) {
            var num = 0.0; var den = 0.0
            listOf(eipZ[month] to 0.40, asiZ[month] to 0.35, fssZ[month] to 0.25)
                .forEach { (v, w) -> if (v != null && !v.isNaN()) { num += v * w; den += w } }
            if (den > 0.0) result.add(month to (num / den).toFloat())
        }
        return result.ifEmpty { null }
    }

    private fun classifyRegime(score: Double) = when {
        score >= 0.5  -> "RESILIENT"
        score >= 0.0  -> "STABLE"
        score >= -0.5 -> "STRESSED"
        score >= -1.0 -> "STRAINED"
        else          -> "BREAKING"
    }
}
