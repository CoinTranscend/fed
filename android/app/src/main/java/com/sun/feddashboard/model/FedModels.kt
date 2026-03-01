package com.sun.feddashboard.model

/** A single month's composite value for charting. */
data class ChartPoint(
    val monthLabel: String,
    val value: Float,
)

/** Per-series component reading at the latest data date. */
data class ComponentReading(
    val seriesId: String,
    val label: String,
    val zScore: Double,        // raw z-score (before inversion)
    val weight: Double,        // series weight (or 1/n for equal-weight)
    val contribution: Double,  // z * sign * weight (signed, ready to sum)
)

/** Result from a leading index (LIIMSI or LLMSI). */
data class LeadingResult(
    val points: List<ChartPoint>,   // last 12 months
    val current: Float,
    val regime: String,
    val updatedAt: String,
    val components: List<ComponentReading>,
    val lastDataMonth: String,
)

/** Result from a regular/coincident index (ISI or LSI). */
data class RegularResult(
    val points: List<ChartPoint>,   // last 12 months
    val current: Float,
    val regime: String,
    val updatedAt: String,
    val components: List<ComponentReading>,
    val lastDataMonth: String,
)
