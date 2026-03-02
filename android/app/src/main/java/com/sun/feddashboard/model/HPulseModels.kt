package com.sun.feddashboard.model

/** One month of HPulse data — all values on the 0–100 stress scale. */
data class HPulsePoint(
    val monthLabel: String,
    val composite: Float,
    val burnScore: Float,
    val middleScore: Float,
    val bufferScore: Float,
)

/** Per-component score for the info dialog. */
data class HPulseComponentScore(
    val seriesId: String,
    val label: String,
    val rawScore: Float,   // 0–100 stress score for this component
    val weight: Float,     // weight within its sub-index
)

/** Full output of one HPulse refresh cycle. */
data class HPulseResult(
    val points: List<HPulsePoint>,              // 48-month history for chart
    val composite: Float,                       // latest weighted composite (0–100)
    val burnScore: Float,                       // Burn Zone tier score
    val middleScore: Float,                     // Middle Pulse tier score
    val bufferScore: Float,                     // Buffer Zone tier score
    val band: String,                           // STABLE / WARMING / STRAINED / BURN
    val updatedAt: String,
    val lastDataMonth: String,
    val essentialsComponents: List<HPulseComponentScore>,
    val debtComponents: List<HPulseComponentScore>,
)

enum class GeminiHPulseStatus {
    IDLE, LOADING, OK, QUOTA, NO_KEY, ERROR
}

data class HPulseNarrativeState(
    val text: String? = null,
    val status: GeminiHPulseStatus = GeminiHPulseStatus.IDLE,
    val loading: Boolean = false,
) {
    val statusMessage: String get() = when (status) {
        GeminiHPulseStatus.QUOTA   -> "⚠  Gemini quota exceeded — AI narrative unavailable"
        GeminiHPulseStatus.NO_KEY  -> "⚠  Add a Gemini API key in Settings for the AI narrative"
        GeminiHPulseStatus.ERROR   -> "⚠  AI narrative unavailable — tap ↻ to retry"
        GeminiHPulseStatus.LOADING -> "Analysing household stress indicators…"
        else                       -> ""
    }
}
