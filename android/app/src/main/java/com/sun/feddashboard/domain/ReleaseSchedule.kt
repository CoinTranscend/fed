package com.sun.feddashboard.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Maps FRED series IDs to their source economic release and computes the next
 * scheduled release date based on standard BLS / BEA / Fed calendar rules.
 *
 * Usage:
 *   val text = ReleaseSchedule.buildReleasesText(ReleaseSchedule.pulseSeriesIds())
 *   binding.tvDataSources.text = text
 */
object ReleaseSchedule {

    enum class Release(val displayName: String) {
        CPI                  ("CPI (BLS)"),
        PPI                  ("PPI (BLS)"),
        EMPLOYMENT_SITUATION ("Employment Situation"),
        JOLTS                ("JOLTS (BLS)"),
        JOBLESS_CLAIMS       ("Jobless Claims"),
        PCE_BEA              ("PCE / Personal Income"),
        UMICH_SENTIMENT      ("UMich Sentiment"),
        CONSUMER_CREDIT      ("Consumer Credit G.19"),
        TREASURY_DAILY       ("Treasury Yields"),
        ICE_DAILY            ("HY Credit Spreads"),
        BUILDING_PERMITS     ("Building Permits"),
        ADVANCE_RETAIL       ("Advance Retail Sales"),
        M2_MONEY             ("M2 Money Stock"),
        COMMODITY_MONTHLY    ("Copper & Gold Prices"),
        FR_DELINQUENCY       ("CC Delinquency (Fed)"),
    }

    // ── Series → Release mapping ──────────────────────────────────────────────

    private val SERIES_RELEASE: Map<String, Release> = mapOf(
        // CPI family (BLS CPI release, ~mid-month following reference month)
        "CPILFESL"              to Release.CPI,
        "CPIUFDSL"              to Release.CPI,
        "CPIAUCSL"              to Release.CPI,
        "CUSR0000SEHA"          to Release.CPI,
        "CUSR0000SETB01"        to Release.CPI,
        "CUSR0000SAH2"          to Release.CPI,
        "CUSR0000SAH1"          to Release.CPI,
        "APU0000708111"         to Release.CPI,
        "APU0000709112"         to Release.CPI,
        "APU0000710411"         to Release.CPI,
        "CORESTICKM159SFRBATL"  to Release.CPI,   // Atlanta Fed Sticky CPI (same day)
        "FLEXCPIM159SFRBATL"    to Release.CPI,   // Atlanta Fed Flexible CPI (same day)
        // PPI (BLS, ~Thursday after CPI Wednesday)
        "PPIFIS"                to Release.PPI,
        "PPIACO"                to Release.PPI,
        // Employment Situation (BLS, first Friday of following month)
        "PAYEMS"                to Release.EMPLOYMENT_SITUATION,
        "UNRATE"                to Release.EMPLOYMENT_SITUATION,
        "CIVPART"               to Release.EMPLOYMENT_SITUATION,
        "CES0500000003"         to Release.EMPLOYMENT_SITUATION,
        "MANEMP"                to Release.EMPLOYMENT_SITUATION,
        "AWHMAN"                to Release.EMPLOYMENT_SITUATION,
        "TEMPHELPS"             to Release.EMPLOYMENT_SITUATION,
        // JOLTS (BLS, 2nd Tuesday ~5-6 weeks after reference month)
        "JTSJOL"                to Release.JOLTS,
        "JTSQUR"                to Release.JOLTS,
        "JTSLDL"                to Release.JOLTS,
        "JTSQUL"                to Release.JOLTS,
        // Jobless Claims (BLS, every Thursday at 8:30 AM ET)
        "ICSA"                  to Release.JOBLESS_CLAIMS,
        "CCSA"                  to Release.JOBLESS_CLAIMS,
        // PCE / Personal Income (BEA, last Friday of following month)
        "PCEPILFE"              to Release.PCE_BEA,
        "PCETRIM12M159SFRBDAL"  to Release.PCE_BEA,
        "PSAVERT"               to Release.PCE_BEA,
        "DSPIC96"               to Release.PCE_BEA,
        // UMich Consumer Sentiment (preliminary: 2nd Friday; final: last Friday)
        "UMCSENT"               to Release.UMICH_SENTIMENT,
        "MICH"                  to Release.UMICH_SENTIMENT,
        // Consumer Credit G.19 (Fed, ~5th business day of following month)
        "REVOLSL"               to Release.CONSUMER_CREDIT,
        // Treasury market data (FRED updates daily, no lag)
        "T10Y2Y"                to Release.TREASURY_DAILY,
        "T10Y3M"                to Release.TREASURY_DAILY,
        "T5YIE"                 to Release.TREASURY_DAILY,
        "T10YIE"                to Release.TREASURY_DAILY,
        "T5YIFR"                to Release.TREASURY_DAILY,
        // ICE BofA High-Yield credit spreads (FRED updates daily)
        "BAMLH0A0HYM2"          to Release.ICE_DAILY,
        // New Residential Construction / Building Permits (Census, ~3rd Thursday)
        "PERMIT"                to Release.BUILDING_PERMITS,
        // Advance Retail Sales (Census, ~2nd Wednesday of following month)
        "MRTSSM4451USS"         to Release.ADVANCE_RETAIL,
        // M2 Money Stock H.6 (Fed, released every ~4 weeks on Thursdays)
        "M2SL"                  to Release.M2_MONEY,
        // Commodity prices: monthly averages (IMF / LBMA, ~5th of following month)
        "PCOPPUSDM"             to Release.COMMODITY_MONTHLY,
        "GOLDAMGBD228NLBM"      to Release.COMMODITY_MONTHLY,
        // Charge-off & Delinquency Rates (Fed, quarterly ~62 days after quarter end)
        "DRCCLACBS"             to Release.FR_DELINQUENCY,
        "CORCCACBS"             to Release.FR_DELINQUENCY,
    )

    // ── Per-index series lists ────────────────────────────────────────────────

    fun pulseSeriesIds() = listOf(
        "CUSR0000SEHA", "CPIUFDSL", "CUSR0000SETB01",
        "APU0000708111", "APU0000709112", "APU0000710411", "CES0500000003",
        "DRCCLACBS", "REVOLSL", "PSAVERT", "CORCCACBS",
        "UMCSENT", "MRTSSM4451USS", "DSPIC96",
    )

    fun hpulseSeriesIds() = listOf(
        "CUSR0000SEHA", "CPIUFDSL", "CUSR0000SETB01", "CUSR0000SAH2",
        "CES0500000003", "REVOLSL", "DRCCLACBS", "PSAVERT",
    )

    fun rriSeriesIds() = listOf(
        "T10Y2Y", "T10Y3M", "BAMLH0A0HYM2", "PERMIT",
        "ICSA", "UMCSENT", "PCOPPUSDM", "GOLDAMGBD228NLBM", "M2SL", "CPIAUCSL",
    )

    fun isiLsiSeriesIds() = listOf(
        // ISI (Inflation Strength Index)
        "CPILFESL", "PCEPILFE", "PCETRIM12M159SFRBDAL", "CORESTICKM159SFRBATL",
        "PPIFIS", "PPIACO", "T5YIE", "T10YIE", "CUSR0000SAH1", "MICH",
        // LSI (Labor Strength Index)
        "PAYEMS", "UNRATE", "CIVPART", "ICSA", "CCSA", "JTSJOL", "JTSQUR",
        "CES0500000003", "MANEMP",
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a formatted monospace text block listing the next release dates
     * for all unique releases covering [seriesIds], sorted by next date.
     * Format: "%-22s  %s" → e.g. "CPI (BLS)               Mar 11"
     */
    fun buildReleasesText(seriesIds: List<String>, today: LocalDate = LocalDate.now()): String {
        val seen  = mutableSetOf<Release>()
        val pairs = mutableListOf<Pair<Release, LocalDate>>()
        for (id in seriesIds) {
            val rel = SERIES_RELEASE[id] ?: continue
            if (seen.add(rel)) pairs.add(rel to nextDate(rel, today))
        }
        pairs.sortBy { it.second }
        return pairs.joinToString("\n") { (rel, date) ->
            "%-22s  %s".format(rel.displayName.take(22), formatDate(rel, date))
        }
    }

    // ── Date formatting ───────────────────────────────────────────────────────

    private fun formatDate(release: Release, date: LocalDate): String = when (release) {
        Release.TREASURY_DAILY,
        Release.ICE_DAILY      -> "daily (current)"
        Release.JOBLESS_CLAIMS -> "Thu ${date.format(MMM_D)}"
        Release.FR_DELINQUENCY -> date.format(MMM_YYYY)
        else                   -> date.format(MMM_D)
    }

    // ── Next-date computation ─────────────────────────────────────────────────

    fun nextDate(release: Release, today: LocalDate): LocalDate = when (release) {
        // BLS CPI: first Wednesday on or after the 10th of each month
        Release.CPI ->
            nextMonthly(today) { y, m -> firstDowOnOrAfter(y, m, 10, DayOfWeek.WEDNESDAY) }

        // BLS PPI: first Thursday on or after the 11th (~1-2 days after CPI)
        Release.PPI ->
            nextMonthly(today) { y, m -> firstDowOnOrAfter(y, m, 11, DayOfWeek.THURSDAY) }

        // BLS Employment Situation: first Friday of the following month
        Release.EMPLOYMENT_SITUATION ->
            nextMonthly(today) { y, m -> nthDow(y, m, DayOfWeek.FRIDAY, 1) }

        // BLS JOLTS: 2nd Tuesday of each month (~5-6 week lag from reference month)
        Release.JOLTS ->
            nextMonthly(today) { y, m -> nthDow(y, m, DayOfWeek.TUESDAY, 2) }

        // BLS Initial/Continuing Claims: every Thursday (weekly, show next Thursday)
        Release.JOBLESS_CLAIMS ->
            nextStrictDow(today, DayOfWeek.THURSDAY)

        // BEA PCE / Personal Income: last Friday of each month
        Release.PCE_BEA ->
            nextMonthly(today) { y, m -> lastDow(y, m, DayOfWeek.FRIDAY) }

        // UMich Sentiment: preliminary = 2nd Friday of month
        Release.UMICH_SENTIMENT ->
            nextMonthly(today) { y, m -> nthDow(y, m, DayOfWeek.FRIDAY, 2) }

        // Fed G.19 Consumer Credit: 5th business day of each month
        Release.CONSUMER_CREDIT ->
            nextMonthly(today) { y, m -> nthBizDay(y, m, 5) }

        // Daily series (Treasury/ICE): always current, next business day shown
        Release.TREASURY_DAILY,
        Release.ICE_DAILY ->
            nextBizDay(today)

        // Census Building Permits: 3rd Thursday of each month (~18th-21st)
        Release.BUILDING_PERMITS ->
            nextMonthly(today) { y, m -> nthDow(y, m, DayOfWeek.THURSDAY, 3) }

        // Census Advance Retail Sales: first Wednesday on or after the 12th
        Release.ADVANCE_RETAIL ->
            nextMonthly(today) { y, m -> firstDowOnOrAfter(y, m, 12, DayOfWeek.WEDNESDAY) }

        // Fed H.6 M2: ~2nd Thursday of each month
        Release.M2_MONEY ->
            nextMonthly(today) { y, m -> nthDow(y, m, DayOfWeek.THURSDAY, 2) }

        // IMF/LBMA commodity prices: ~5th of each month (skip weekend)
        Release.COMMODITY_MONTHLY ->
            nextMonthly(today) { y, m ->
                var d = LocalDate.of(y, m, 5)
                if (d.dayOfWeek == DayOfWeek.SATURDAY) d = d.plusDays(2)
                else if (d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
                d
            }

        // Fed Charge-off/Delinquency: quarterly, ~62 days after quarter end
        Release.FR_DELINQUENCY ->
            nextQuarterly(today)
    }

    // ── Calendar helpers ─────────────────────────────────────────────────────

    /** Run [fn] for current month; if result is before today, run for next month. */
    private fun nextMonthly(today: LocalDate, fn: (Int, Int) -> LocalDate): LocalDate {
        val c = fn(today.year, today.monthValue)
        if (c >= today) return c
        val n = today.plusMonths(1)
        return fn(n.year, n.monthValue)
    }

    /** First occurrence of [dow] on or after the [minDay]th of the given month. */
    private fun firstDowOnOrAfter(y: Int, m: Int, minDay: Int, dow: DayOfWeek): LocalDate {
        var d = LocalDate.of(y, m, minDay)
        while (d.dayOfWeek != dow) d = d.plusDays(1)
        return d
    }

    /** The [n]th occurrence (1-based) of [dow] in the given month. */
    private fun nthDow(y: Int, m: Int, dow: DayOfWeek, n: Int): LocalDate {
        var d = LocalDate.of(y, m, 1)
        while (d.dayOfWeek != dow) d = d.plusDays(1)
        return d.plusWeeks((n - 1).toLong())
    }

    /** Last occurrence of [dow] in the given month. */
    private fun lastDow(y: Int, m: Int, dow: DayOfWeek): LocalDate {
        var d = LocalDate.of(y, m, 1).plusMonths(1).minusDays(1)
        while (d.dayOfWeek != dow) d = d.minusDays(1)
        return d
    }

    /** The [n]th business day (Mon–Fri, 1-based) in the given month. */
    private fun nthBizDay(y: Int, m: Int, n: Int): LocalDate {
        var d = LocalDate.of(y, m, 1)
        var count = 0
        while (true) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) {
                count++
                if (count == n) return d
            }
            d = d.plusDays(1)
        }
    }

    /** Strictly next occurrence of [dow] after today (not including today). */
    private fun nextStrictDow(today: LocalDate, dow: DayOfWeek): LocalDate {
        var d = today.plusDays(1)
        while (d.dayOfWeek != dow) d = d.plusDays(1)
        return d
    }

    /** Strictly next business day after today. */
    private fun nextBizDay(today: LocalDate): LocalDate {
        var d = today.plusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
        return d
    }

    /** Next quarterly Fed release: ~62 days after a quarter end (Mar/Jun/Sep/Dec). */
    private fun nextQuarterly(today: LocalDate): LocalDate {
        val ends = buildList {
            for (yr in (today.year - 1)..(today.year + 1)) {
                add(LocalDate.of(yr,  3, 31))
                add(LocalDate.of(yr,  6, 30))
                add(LocalDate.of(yr,  9, 30))
                add(LocalDate.of(yr, 12, 31))
            }
        }.sorted()
        for (qEnd in ends) {
            val rel = qEnd.plusDays(62)
            if (rel >= today) return rel
        }
        return today.plusMonths(3)
    }

    private val MMM_D    = DateTimeFormatter.ofPattern("MMM d")
    private val MMM_YYYY = DateTimeFormatter.ofPattern("MMM yyyy")
}
