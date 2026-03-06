package com.sun.feddashboard.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Maps FRED series IDs to their source economic release and computes:
 *   (a) the latest data vintage currently available, and
 *   (b) the next scheduled release date
 * using standard BLS / BEA / Fed calendar rules — no API calls required.
 *
 * Data-lag rules per release type:
 *   CPI, PPI, Employment Situation, PCE, Building Permits,
 *   Advance Retail, M2, Commodities → 1-month lag (release month N = data month N-1)
 *   JOLTS, Consumer Credit G.19     → 2-month lag (release month N = data month N-2)
 *   UMich Sentiment                 → 0-month lag (released for the current month)
 *   Jobless Claims                  → ~5-day lag (week ending prior Saturday)
 *   Treasury / ICE daily series     → effectively 0 (daily update)
 *   Fed Charge-off / Delinquency    → quarterly, ~62 days after quarter end
 *
 * Usage:
 *   binding.tvDataSources.text = ReleaseSchedule.buildReleasesText(ReleaseSchedule.pulseSeriesIds())
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
        "CORESTICKM159SFRBATL"  to Release.CPI,
        "FLEXCPIM159SFRBATL"    to Release.CPI,
        "PPIFIS"                to Release.PPI,
        "PPIACO"                to Release.PPI,
        "PAYEMS"                to Release.EMPLOYMENT_SITUATION,
        "UNRATE"                to Release.EMPLOYMENT_SITUATION,
        "CIVPART"               to Release.EMPLOYMENT_SITUATION,
        "CES0500000003"         to Release.EMPLOYMENT_SITUATION,
        "MANEMP"                to Release.EMPLOYMENT_SITUATION,
        "AWHMAN"                to Release.EMPLOYMENT_SITUATION,
        "TEMPHELPS"             to Release.EMPLOYMENT_SITUATION,
        "JTSJOL"                to Release.JOLTS,
        "JTSQUR"                to Release.JOLTS,
        "JTSLDL"                to Release.JOLTS,
        "JTSQUL"                to Release.JOLTS,
        "ICSA"                  to Release.JOBLESS_CLAIMS,
        "CCSA"                  to Release.JOBLESS_CLAIMS,
        "PCEPILFE"              to Release.PCE_BEA,
        "PCETRIM12M159SFRBDAL"  to Release.PCE_BEA,
        "PSAVERT"               to Release.PCE_BEA,
        "DSPIC96"               to Release.PCE_BEA,
        "UMCSENT"               to Release.UMICH_SENTIMENT,
        "MICH"                  to Release.UMICH_SENTIMENT,
        "REVOLSL"               to Release.CONSUMER_CREDIT,
        "T10Y2Y"                to Release.TREASURY_DAILY,
        "T10Y3M"                to Release.TREASURY_DAILY,
        "T5YIE"                 to Release.TREASURY_DAILY,
        "T10YIE"                to Release.TREASURY_DAILY,
        "T5YIFR"                to Release.TREASURY_DAILY,
        "BAMLH0A0HYM2"          to Release.ICE_DAILY,
        "PERMIT"                to Release.BUILDING_PERMITS,
        "MRTSSM4451USS"         to Release.ADVANCE_RETAIL,
        "M2SL"                  to Release.M2_MONEY,
        "PCOPPUSDM"             to Release.COMMODITY_MONTHLY,
        "GOLDAMGBD228NLBM"      to Release.COMMODITY_MONTHLY,
        "DRCCLACBS"             to Release.FR_DELINQUENCY,
        "CORCCACBS"             to Release.FR_DELINQUENCY,
        // Computed composites used internally by RecessionEngine
        "COPPER_GOLD"           to Release.COMMODITY_MONTHLY,
        "REAL_M2"               to Release.M2_MONEY,
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
        "CPILFESL", "PCEPILFE", "PCETRIM12M159SFRBDAL", "CORESTICKM159SFRBATL",
        "PPIFIS", "PPIACO", "T5YIE", "T10YIE", "CUSR0000SAH1", "MICH",
        "PAYEMS", "UNRATE", "CIVPART", "ICSA", "CCSA", "JTSJOL", "JTSQUR",
        "CES0500000003", "MANEMP",
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Builds a monospace text block sorted by next release date.
     * Three columns per row:
     *   %-22s  %-8s  → %s
     *   [source report]  [last release date]  [next release date]
     *
     * Example (today = March 6, 2026 — Employment Situation released today):
     *   Employment Situa    Mar 6   → Apr 3
     *   JOLTS (BLS)         Feb 4   → Mar 10
     *   CPI (BLS)           Feb 12  → Mar 11
     *   Jobless Claims      Mar 5   → Thu Mar 12
     *   UMich Sentiment     Feb 28  → Mar 13
     *   Treasury Yields     daily   → daily
     */
    fun buildReleasesText(seriesIds: List<String>, today: LocalDate = LocalDate.now()): String {
        val seen = mutableSetOf<Release>()
        data class Row(val rel: Release, val lastLbl: String, val nextDt: LocalDate, val nextLbl: String)
        val rows = mutableListOf<Row>()

        for (id in seriesIds) {
            val rel = SERIES_RELEASE[id] ?: continue
            if (!seen.add(rel)) continue
            val nextDt = nextDate(rel, today)
            rows.add(Row(rel, latestReleaseLabel(rel, today), nextDt, formatNextDate(rel, nextDt)))
        }
        rows.sortBy { it.nextDt }
        return rows.joinToString("\n") { r ->
            "%-22s  %-8s  → %s".format(r.rel.displayName.take(22), r.lastLbl, r.nextLbl)
        }
    }

    // ── Last release date label ───────────────────────────────────────────────

    /**
     * Returns a short label for when this release was most recently published.
     * Examples: "Mar 6", "Feb 12", "Mar 5", "daily", "Dec 1"
     */
    private fun latestReleaseLabel(release: Release, today: LocalDate): String = when (release) {

        Release.TREASURY_DAILY,
        Release.ICE_DAILY -> "daily"

        Release.JOBLESS_CLAIMS -> {
            // Released every Thursday — show the most recent Thursday on or before today
            val lastThursday = mostRecentDow(today, DayOfWeek.THURSDAY)
            lastThursday.format(FMT_MMM_D)
        }

        Release.FR_DELINQUENCY -> {
            // Quarterly — show the most recent quarterly release date
            quarterReleases(today)
                .filter { (releaseDate, _) -> releaseDate <= today }
                .maxByOrNull { it.first }?.first?.format(FMT_MMM_D) ?: "pending"
        }

        else -> {
            // Monthly — show the calendar date of the most recent release
            val lastRel = latestMonthlyReleaseDate(release, today)
            lastRel.format(FMT_MMM_D)
        }
    }

    // ── Latest data vintage label (kept for potential future use) ─────────────

    /**
     * Returns the data period covered by the most recent release.
     * Examples: "Jan 2026", "Feb 2026", "wk Mar 1", "Q4 2025", "current"
     */
    fun latestDataLabel(release: Release, today: LocalDate): String = when (release) {

        Release.TREASURY_DAILY,
        Release.ICE_DAILY ->
            // Updated daily — data is effectively current
            "current"

        Release.JOBLESS_CLAIMS -> {
            // Released every Thursday; data covers the week ending the prior Saturday (~5 days back).
            // If Thursday has not yet occurred this week, use last Thursday.
            val lastThursday = mostRecentDow(today, DayOfWeek.THURSDAY)
            val weekEnding   = lastThursday.minusDays(5)  // prior Saturday
            "wk ${weekEnding.format(FMT_MMM_D)}"
        }

        Release.FR_DELINQUENCY -> {
            // Quarterly, ~62-day lag.  Find the most recently published quarter.
            quarterReleases(today)
                .filter { (releaseDate, _) -> releaseDate <= today }
                .maxByOrNull { it.first }?.second ?: "Q? ????"
        }

        Release.UMICH_SENTIMENT -> {
            // 0-month lag: published data is for the same calendar month as the release.
            // Preliminary mid-month, final end-of-month — use whichever last occurred.
            val lastRel = latestMonthlyReleaseDate(release, today)
            lastRel.format(FMT_MON_YYYY)
        }

        Release.JOLTS,
        Release.CONSUMER_CREDIT -> {
            // 2-month lag: e.g., March release → January data.
            val lastRel = latestMonthlyReleaseDate(release, today)
            lastRel.minusMonths(2).format(FMT_MON_YYYY)
        }

        else -> {
            // Standard 1-month lag: e.g., March CPI release → February data.
            // Critically: if the release happened *today* (e.g., Employment Situation on March 6),
            // we correctly show the just-released month (Feb 2026), not the prior one.
            val lastRel = latestMonthlyReleaseDate(release, today)
            lastRel.minusMonths(1).format(FMT_MON_YYYY)
        }
    }

    // ── Next release date ─────────────────────────────────────────────────────

    /**
     * Returns the next scheduled release date strictly after [today].
     * If a release occurred *today* (e.g., Employment Situation on its first-Friday),
     * this returns the following month's release, not today — so users always see
     * an upcoming date, not a "just happened" date.
     */
    fun nextDate(release: Release, today: LocalDate): LocalDate = when (release) {

        Release.JOBLESS_CLAIMS ->
            nextStrictDow(today, DayOfWeek.THURSDAY)

        Release.TREASURY_DAILY,
        Release.ICE_DAILY ->
            nextBizDay(today)

        Release.FR_DELINQUENCY ->
            quarterReleases(today).map { it.first }.filter { it > today }.minOrNull()
                ?: today.plusMonths(3)

        else -> {
            // Monthly release: use > today (strict) so a release *today* returns next month.
            val c = candidateDate(release, today.year, today.monthValue)
            if (c != null && c > today) c else {
                val n = today.plusMonths(1)
                candidateDate(release, n.year, n.monthValue) ?: n
            }
        }
    }

    // ── Most recent monthly release on or before today ────────────────────────

    private fun latestMonthlyReleaseDate(release: Release, today: LocalDate): LocalDate {
        val c = candidateDate(release, today.year, today.monthValue)
        // <= today means the release already happened (including today)
        if (c != null && c <= today) return c
        val p = today.minusMonths(1)
        return candidateDate(release, p.year, p.monthValue) ?: p
    }

    // ── Candidate release date for a given calendar month ────────────────────

    private fun candidateDate(release: Release, y: Int, m: Int): LocalDate? = when (release) {
        Release.CPI ->
            firstDowOnOrAfter(y, m, 10, DayOfWeek.WEDNESDAY)
        Release.PPI ->
            firstDowOnOrAfter(y, m, 11, DayOfWeek.THURSDAY)
        Release.EMPLOYMENT_SITUATION ->
            nthDow(y, m, DayOfWeek.FRIDAY, 1)
        Release.JOLTS ->
            nthDow(y, m, DayOfWeek.TUESDAY, 2)
        Release.PCE_BEA ->
            lastDow(y, m, DayOfWeek.FRIDAY)
        Release.UMICH_SENTIMENT ->
            nthDow(y, m, DayOfWeek.FRIDAY, 2)
        Release.CONSUMER_CREDIT ->
            nthBizDay(y, m, 5)
        Release.BUILDING_PERMITS ->
            nthDow(y, m, DayOfWeek.THURSDAY, 3)
        Release.ADVANCE_RETAIL ->
            firstDowOnOrAfter(y, m, 12, DayOfWeek.WEDNESDAY)
        Release.M2_MONEY ->
            nthDow(y, m, DayOfWeek.THURSDAY, 2)
        Release.COMMODITY_MONTHLY -> {
            var d = LocalDate.of(y, m, 5)
            if (d.dayOfWeek == DayOfWeek.SATURDAY) d = d.plusDays(2)
            else if (d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
            d
        }
        else -> null  // non-monthly — handled separately in nextDate / latestDataLabel
    }

    // ── Quarterly release schedule ────────────────────────────────────────────

    private fun quarterReleases(today: LocalDate): List<Pair<LocalDate, String>> {
        val result = mutableListOf<Pair<LocalDate, String>>()
        for (yr in (today.year - 2)..(today.year + 2)) {
            // Q4 of yr:  data Oct–Dec yr  → released ~Mar 3 of (yr+1)  [Dec 31 + 62 days]
            result.add(LocalDate.of(yr + 1, 1, 1).plusDays(61) to "Q4 $yr")
            // Q1 of yr:  data Jan–Mar yr  → released ~Jun 1 of yr       [Mar 31 + 62 days]
            result.add(LocalDate.of(yr, 3, 31).plusDays(62) to "Q1 $yr")
            // Q2 of yr:  data Apr–Jun yr  → released ~Sep 1 of yr       [Jun 30 + 62 days]
            result.add(LocalDate.of(yr, 6, 30).plusDays(62) to "Q2 $yr")
            // Q3 of yr:  data Jul–Sep yr  → released ~Dec 1 of yr       [Sep 30 + 62 days]
            result.add(LocalDate.of(yr, 9, 30).plusDays(62) to "Q3 $yr")
        }
        return result.sortedBy { it.first }
    }

    // ── Format helpers ────────────────────────────────────────────────────────

    private fun formatNextDate(release: Release, date: LocalDate): String = when (release) {
        Release.TREASURY_DAILY,
        Release.ICE_DAILY      -> "daily"
        Release.JOBLESS_CLAIMS -> "Thu ${date.format(FMT_MMM_D)}"
        Release.FR_DELINQUENCY -> date.format(FMT_MON_YYYY)
        else                   -> date.format(FMT_MMM_D)
    }

    // ── Calendar helpers ─────────────────────────────────────────────────────

    /** First [dow] on or after the [minDay]th of the given month. */
    private fun firstDowOnOrAfter(y: Int, m: Int, minDay: Int, dow: DayOfWeek): LocalDate {
        var d = LocalDate.of(y, m, minDay)
        while (d.dayOfWeek != dow) d = d.plusDays(1)
        return d
    }

    /** The [n]th (1-based) occurrence of [dow] in the given month. */
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

    /** The [n]th (1-based) business day (Mon–Fri) in the given month. */
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

    /** Most recent occurrence of [dow] on or before [today] (returns today if today matches). */
    private fun mostRecentDow(today: LocalDate, dow: DayOfWeek): LocalDate {
        var d = today
        while (d.dayOfWeek != dow) d = d.minusDays(1)
        return d
    }

    /** Strictly next occurrence of [dow] after [today]. */
    private fun nextStrictDow(today: LocalDate, dow: DayOfWeek): LocalDate {
        var d = today.plusDays(1)
        while (d.dayOfWeek != dow) d = d.plusDays(1)
        return d
    }

    /** Strictly next business day after [today]. */
    private fun nextBizDay(today: LocalDate): LocalDate {
        var d = today.plusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
        return d
    }

    private val FMT_MMM_D    = DateTimeFormatter.ofPattern("MMM d")
    private val FMT_MON_YYYY = DateTimeFormatter.ofPattern("MMM yyyy")
}
