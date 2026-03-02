package com.sun.feddashboard.model

/** Full output of the PULSE engine for one refresh cycle. */
data class PulseResult(
    val points: List<ChartPoint>,          // 48-month composite history
    val current: Float,                    // latest PULSE composite z-score
    val regime: String,                    // RESILIENT / STABLE / STRESSED / STRAINED / BREAKING
    val updatedAt: String,
    val lastDataMonth: String,

    // Sub-index scores (each in z-score space, higher = more resilient)
    val eipScore: Float,                   // Essentials Inflation Pressure
    val asiScore: Float,                   // Affordability Squeeze Index
    val fssScore: Float,                   // Forward Stress Signal

    // Quintile burden breakdowns (raw, pre-z-score; negative = wage trailing prices)
    val q1Burden: Float,                   // bottom 20 % household burden
    val q2Burden: Float,                   // 20–40 % household burden
    val q3Burden: Float,                   // 40–60 % household burden

    // Component details for info dialog
    val eipComponents: List<ComponentReading>,
    val asiComponents: List<ComponentReading>,
    val fssComponents: List<ComponentReading>,
)

enum class GeminiPulseStatus {
    IDLE,       // not yet fetched
    LOADING,    // request in flight
    OK,         // narrative text available
    QUOTA,      // HTTP 429 — quota exceeded
    NO_KEY,     // Gemini key not set
    ERROR,      // other failure
}

data class PulseNarrativeState(
    val text: String? = null,
    val status: GeminiPulseStatus = GeminiPulseStatus.IDLE,
    val loading: Boolean = false,
) {
    /** Human-readable status line shown instead of narrative on failure. */
    val statusMessage: String get() = when (status) {
        GeminiPulseStatus.QUOTA   -> "⚠  Gemini quota exceeded — AI narrative unavailable"
        GeminiPulseStatus.NO_KEY  -> "⚠  Add a Gemini API key in Settings for the AI narrative"
        GeminiPulseStatus.ERROR   -> "⚠  AI narrative unavailable — tap ↻ to retry"
        GeminiPulseStatus.LOADING -> "Analysing consumer stress indicators…"
        else                      -> ""
    }
}
