package com.sun.feddashboard.domain

import com.sun.feddashboard.model.HPulseComponentScore
import com.sun.feddashboard.model.HPulsePoint
import com.sun.feddashboard.model.HPulseResult
import com.sun.feddashboard.network.FredClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * HPulse — Household Pulse Index
 *
 * Produces 0–100 stress scores (100 = maximum stress) for three income tiers:
 *   Burn Zone    (≤ $65,100 /yr)    — 59 % of US households
 *   Middle Pulse ($65,101–$105,500) — 27 %
 *   Buffer Zone  ($105,501–$175,700)— 14 %
 *
 * Essentials Pressure uses real burden = price_YoY − AHE_YoY (fractional).
 * Positive burden → prices outpacing wages → higher stress score.
 *
 * Debt Stress = 0.40 × CardBurden + 0.40 × Delinquency + 0.20 × Cushion.
 *
 * Tier composites:
 *   Burn:   0.60 × Essentials + 0.40 × Debt
 *   Middle: 0.70 × Essentials + 0.30 × Debt
 *   Buffer: 0.75 × Essentials + 0.25 × Debt
 *
 * Composite = 0.59 × Burn + 0.27 × Middle + 0.14 × Buffer
 * Bands: 0–24 STABLE | 25–49 WARMING | 50–74 STRAINED | 75–100 BURN
 */
object HPulseEngine {

    // ── FRED series ───────────────────────────────────────────────────────────

    private const val SHELTER  = "CUSR0000SEHA"    // Shelter CPI, YoY
    private const val FOOD     = "CPIUFDSL"         // Food at Home CPI, YoY
    private const val GAS      = "CUSR0000SETB01"  // Gasoline CPI, YoY
    private const val UTILITY  = "CUSR0000SAH2"    // Household Energy CPI, YoY
    private const val AHE      = "CES0500000003"   // Avg Hourly Earnings, YoY
    private const val REVOLSL  = "REVOLSL"          // Revolving CC Balances, YoY
    private const val DELINQ   = "DRCCLACBS"       // CC Delinquency Rate, level % (quarterly)
    private const val PSAVERT  = "PSAVERT"          // Personal Savings Rate, level %

    // ── Tier essentials weights [housing, grocery, gas, utility] ─────────────
    // Source: BLS Consumer Expenditure Survey 2022-23, CEX budget shares by income quintile.

    private val BURN_ESS_W   = doubleArrayOf(0.68, 0.15, 0.06, 0.11)
    private val MIDDLE_ESS_W = doubleArrayOf(0.64, 0.16, 0.07, 0.13)
    private val BUFFER_ESS_W = doubleArrayOf(0.58, 0.18, 0.09, 0.15)

    // ── Public API ────────────────────────────────────────────────────────────

    fun compute(fredApiKey: String): HPulseResult? {
        if (fredApiKey.isBlank()) return null

        val startDate = LocalDate.now().minusYears(8)  // extra year for YoY calculation
            .format(DateTimeFormatter.ISO_LOCAL_DATE)

        val allIds = listOf(SHELTER, FOOD, GAS, UTILITY, AHE, REVOLSL, DELINQ, PSAVERT)
        val rawSeries = mutableMapOf<String, List<Pair<String, Double?>>>()
        for (id in allIds) {
            val data = try { FredClient.fetchSeries(id, fredApiKey, startDate) }
                       catch (_: Exception) { emptyList() }
            if (data.isNotEmpty()) rawSeries[id] = data
        }

        // Require at minimum AHE + at least one price series
        if (rawSeries[AHE] == null || (rawSeries[SHELTER] == null && rawSeries[FOOD] == null)) return null

        val allMonths = rawSeries.values
            .flatMap { it.map { p -> p.first } }
            .toSortedSet().toList()
        if (allMonths.isEmpty()) return null

        // ── YoY transforms ────────────────────────────────────────────────────

        fun yoy(id: String): Map<String, Double?> {
            val raw = rawSeries[id] ?: return emptyMap()
            val filled = EngineBase.forwardFill(allMonths, raw.toMap())
            return EngineBase.applyYoY(allMonths, filled)
        }

        fun level(id: String): Map<String, Double?> {
            val raw = rawSeries[id] ?: return emptyMap()
            return EngineBase.forwardFill(allMonths, raw.toMap(), limit = 4) // quarterly series
        }

        val shelterYoY  = yoy(SHELTER)
        val foodYoY     = yoy(FOOD)
        val gasYoY      = yoy(GAS)
        val utilYoY     = yoy(UTILITY)
        val aheYoY      = yoy(AHE)
        val revolYoY    = yoy(REVOLSL)
        val delinqLevel = level(DELINQ)
        val psavertLvl  = level(PSAVERT)

        // ── Score functions — all return 0–100 ────────────────────────────────

        fun clamp(v: Double) = max(0.0, min(100.0, v))

        // burden = price_YoY - AHE_YoY  (positive = prices outpacing wages)
        fun burden(priceYoY: Double?, ahe: Double?): Double? =
            if (priceYoY == null || ahe == null) null else priceYoY - ahe

        fun housingScore(b: Double?)  = b?.let { clamp((it / 0.06) * 100.0) }
        fun groceryScore(b: Double?)  = b?.let { clamp((it / 0.08) * 100.0) }
        fun gasScore(b: Double?)      = b?.let { clamp(((it + 0.15) / 0.30) * 100.0) }
        fun utilityScore(b: Double?)  = b?.let { clamp((it / 0.10) * 100.0) }
        fun cardScore(y: Double?)     = y?.let { clamp((it / 0.15) * 100.0) }
        fun delinqScore(r: Double?)   = r?.let { clamp(((it - 1.5) / 5.5) * 100.0) }
        fun cushionScore(s: Double?)  = s?.let { clamp(((8.0 - it) / 6.0) * 100.0) }

        fun essentials(
            housing: Double?, grocery: Double?, gas: Double?, utility: Double?,
            weights: DoubleArray,
        ): Double? {
            var num = 0.0; var den = 0.0
            listOf(housing to weights[0], grocery to weights[1],
                   gas to weights[2], utility to weights[3]).forEach { (v, w) ->
                if (v != null) { num += v * w; den += w }
            }
            return if (den > 0.0) num / den else null
        }

        fun debtStress(card: Double?, delinq: Double?, cushion: Double?): Double? {
            var num = 0.0; var den = 0.0
            listOf(card to 0.40, delinq to 0.40, cushion to 0.20).forEach { (v, w) ->
                if (v != null) { num += v * w; den += w }
            }
            return if (den > 0.0) num / den else null
        }

        fun tierComposite(ess: Double?, debt: Double?, essW: Double, debtW: Double): Double? {
            if (ess == null && debt == null) return null
            var num = 0.0; var den = 0.0
            if (ess  != null) { num += ess  * essW;  den += essW  }
            if (debt != null) { num += debt * debtW; den += debtW }
            return if (den > 0.0) num / den else null
        }

        // ── Per-month computation ─────────────────────────────────────────────

        data class MonthResult(
            val housing: Double?,
            val grocery: Double?,
            val gas: Double?,
            val utility: Double?,
            val card: Double?,
            val delinq: Double?,
            val cushion: Double?,
            val burn: Double?,
            val middle: Double?,
            val buffer: Double?,
            val composite: Double?,
        )

        val monthlyResults = LinkedHashMap<String, MonthResult>()

        for (month in allMonths) {
            val ahe = aheYoY[month]

            val housing = housingScore(burden(shelterYoY[month], ahe))
            val grocery = groceryScore(burden(foodYoY[month], ahe))
            val gas     = gasScore(burden(gasYoY[month], ahe))
            val utility = utilityScore(burden(utilYoY[month], ahe))
            val card    = cardScore(revolYoY[month])
            val delinq  = delinqScore(delinqLevel[month])
            val cushion = cushionScore(psavertLvl[month])
            val debt    = debtStress(card, delinq, cushion)

            val burnEss   = essentials(housing, grocery, gas, utility, BURN_ESS_W)
            val middleEss = essentials(housing, grocery, gas, utility, MIDDLE_ESS_W)
            val bufferEss = essentials(housing, grocery, gas, utility, BUFFER_ESS_W)

            val burn   = tierComposite(burnEss,   debt, 0.60, 0.40)
            val middle = tierComposite(middleEss, debt, 0.70, 0.30)
            val buffer = tierComposite(bufferEss, debt, 0.75, 0.25)

            var cNum = 0.0; var cDen = 0.0
            listOf(burn to 0.59, middle to 0.27, buffer to 0.14).forEach { (v, w) ->
                if (v != null) { cNum += v * w; cDen += w }
            }
            val composite = if (cDen > 0.0) cNum / cDen else null

            if (composite != null) {
                monthlyResults[month] = MonthResult(
                    housing, grocery, gas, utility, card, delinq, cushion,
                    burn, middle, buffer, composite,
                )
            }
        }

        if (monthlyResults.isEmpty()) return null

        val sortedMonths = monthlyResults.keys.sorted()
        val lastMonth    = sortedMonths.last()
        val last         = monthlyResults[lastMonth]!!
        val composite    = last.composite!!.toFloat()

        // ── Historical chart points (last 48 months) ──────────────────────────

        val points = sortedMonths.takeLast(48).map { m ->
            val r = monthlyResults[m]!!
            HPulsePoint(
                monthLabel  = EngineBase.formatMonthLabel(m),
                composite   = r.composite!!.toFloat(),
                burnScore   = r.burn?.toFloat()   ?: r.composite.toFloat(),
                middleScore = r.middle?.toFloat() ?: r.composite.toFloat(),
                bufferScore = r.buffer?.toFloat() ?: r.composite.toFloat(),
            )
        }

        if (points.isEmpty()) return null

        // ── Component breakdowns ──────────────────────────────────────────────

        val essComponents = listOfNotNull(
            last.housing?.let { HPulseComponentScore(SHELTER, "Shelter CPI",      it.toFloat(), 0.68f) },
            last.grocery?.let { HPulseComponentScore(FOOD,    "Food at Home CPI", it.toFloat(), 0.15f) },
            last.gas?.let     { HPulseComponentScore(GAS,     "Gasoline CPI",     it.toFloat(), 0.06f) },
            last.utility?.let { HPulseComponentScore(UTILITY, "Household Energy", it.toFloat(), 0.11f) },
        )

        val debtComponents = listOfNotNull(
            last.card?.let    { HPulseComponentScore(REVOLSL, "CC Revolving YoY",  it.toFloat(), 0.40f) },
            last.delinq?.let  { HPulseComponentScore(DELINQ,  "CC Delinquency %",  it.toFloat(), 0.40f) },
            last.cushion?.let { HPulseComponentScore(PSAVERT, "Savings Cushion",   it.toFloat(), 0.20f) },
        )

        return HPulseResult(
            points                = points,
            composite             = composite,
            burnScore             = last.burn?.toFloat()   ?: composite,
            middleScore           = last.middle?.toFloat() ?: composite,
            bufferScore           = last.buffer?.toFloat() ?: composite,
            band                  = classifyBand(composite),
            updatedAt             = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd HH:mm")),
            lastDataMonth         = EngineBase.formatMonthLabel(lastMonth),
            essentialsComponents  = essComponents,
            debtComponents        = debtComponents,
        )
    }

    fun classifyBand(score: Float) = when {
        score >= 75f -> "BURN"
        score >= 50f -> "STRAINED"
        score >= 25f -> "WARMING"
        else         -> "STABLE"
    }
}
