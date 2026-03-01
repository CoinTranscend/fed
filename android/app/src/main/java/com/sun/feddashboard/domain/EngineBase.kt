package com.sun.feddashboard.domain

import kotlin.math.sqrt

/** Shared math helpers reused by all three engines. */
internal object EngineBase {

    /** Forward-fill null values up to [limit] consecutive slots. */
    fun forwardFill(
        months: List<String>,
        data: Map<String, Double?>,
        limit: Int = 2,
    ): Map<String, Double?> {
        val result = LinkedHashMap<String, Double?>()
        var lastValue: Double? = null
        var gapCount = 0
        for (month in months) {
            val v = data[month]
            when {
                v != null -> { result[month] = v; lastValue = v; gapCount = 0 }
                lastValue != null && gapCount < limit -> { result[month] = lastValue; gapCount++ }
                else -> { result[month] = null; if (lastValue != null) gapCount++ }
            }
        }
        return result
    }

    /** Year-over-year fractional change: (v[i] / v[i-12]) - 1 */
    fun applyYoY(months: List<String>, data: Map<String, Double?>): Map<String, Double?> {
        val result = LinkedHashMap<String, Double?>()
        for (i in months.indices) {
            val month = months[i]
            val current = data[month]
            if (current == null || i < 12) { result[month] = null; continue }
            val prior = data[months[i - 12]]
            result[month] = if (prior == null || prior == 0.0) null else (current / prior) - 1.0
        }
        return result
    }

    /** Rolling z-score (window=[window], minPeriods=[minPeriods], sample std). */
    fun rollingZScore(
        months: List<String>,
        data: Map<String, Double?>,
        window: Int = 60,
        minPeriods: Int = 24,
    ): Map<String, Double?> {
        val result = LinkedHashMap<String, Double?>()
        for (i in months.indices) {
            val month = months[i]
            val current = data[month]
            if (current == null) { result[month] = null; continue }
            val startIdx = maxOf(0, i - window + 1)
            val windowVals = (startIdx..i).mapNotNull { data[months[it]] }
            if (windowVals.size < minPeriods) { result[month] = null; continue }
            val mean = windowVals.average()
            val std  = sampleStd(windowVals, mean)
            result[month] = if (std < 1e-10) 0.0 else (current - mean) / std
        }
        return result
    }

    private fun sampleStd(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(variance)
    }

    /** Format "YYYY-MM" → "Jan '24" */
    fun formatMonthLabel(yearMonth: String): String {
        val parts = yearMonth.split("-")
        val year  = parts.getOrNull(0)?.toIntOrNull() ?: return yearMonth
        val month = parts.getOrNull(1)?.toIntOrNull() ?: return yearMonth
        val abbr  = arrayOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                             "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val yr2 = (year % 100).toString().padStart(2, '0')
        return "${abbr.getOrElse(month) { "?" }} '$yr2"
    }
}
