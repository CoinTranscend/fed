package com.sun.feddashboard

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.sun.feddashboard.domain.FedEngine
import com.sun.feddashboard.domain.HPulseEngine
import com.sun.feddashboard.domain.InflationLeadingEngine
import com.sun.feddashboard.domain.LaborLeadingEngine
import com.sun.feddashboard.domain.PulseEngine
import com.sun.feddashboard.domain.RecessionEngine
import com.sun.feddashboard.ui.ChartExporter
import com.sun.feddashboard.model.GeminiHPulseStatus
import com.sun.feddashboard.model.GeminiPulseStatus
import com.sun.feddashboard.model.HPulseNarrativeState
import com.sun.feddashboard.model.HPulseResult
import com.sun.feddashboard.model.LeadingResult
import com.sun.feddashboard.model.PulseNarrativeState
import com.sun.feddashboard.model.PulseResult
import com.sun.feddashboard.model.RegularResult
import com.sun.feddashboard.network.GeminiClient
import com.sun.feddashboard.network.GeminiHPulseClient
import com.sun.feddashboard.network.GeminiPulseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("fed_prefs", Context.MODE_PRIVATE)
    private val gson  = Gson()

    // ── Keys ──────────────────────────────────────────────────────────────────

    private val _fredKey   = MutableStateFlow(prefs.getString(KEY_FRED,   "") ?: "")
    private val _geminiKey = MutableStateFlow(prefs.getString(KEY_GEMINI, "") ?: "")
    val fredKey   = _fredKey.asStateFlow()
    val geminiKey = _geminiKey.asStateFlow()

    fun saveFredKey(key: String) {
        prefs.edit().putString(KEY_FRED, key.trim()).apply()
        _fredKey.value = key.trim()
    }

    fun saveGeminiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI, key.trim()).apply()
        _geminiKey.value = key.trim()
    }

    fun loadFredKey()   = prefs.getString(KEY_FRED,   "") ?: ""
    fun loadGeminiKey() = prefs.getString(KEY_GEMINI, "") ?: ""

    // ── Loading ───────────────────────────────────────────────────────────────

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _geminiLoading = MutableStateFlow(false)
    val geminiLoading = _geminiLoading.asStateFlow()

    // ── Index results ─────────────────────────────────────────────────────────

    private val _isiResult    = MutableStateFlow<RegularResult?>(null)
    private val _lsiResult    = MutableStateFlow<RegularResult?>(null)
    private val _liimsiResult = MutableStateFlow<LeadingResult?>(null)
    private val _llmsiResult  = MutableStateFlow<LeadingResult?>(null)
    private val _rriResult    = MutableStateFlow<LeadingResult?>(null)

    val isiResult    = _isiResult.asStateFlow()
    val lsiResult    = _lsiResult.asStateFlow()
    val liimsiResult = _liimsiResult.asStateFlow()
    val llmsiResult  = _llmsiResult.asStateFlow()
    val rriResult    = _rriResult.asStateFlow()

    // ── Gemini narrative ──────────────────────────────────────────────────────

    private val _recessionNarrative = MutableStateFlow<String?>(null)
    val recessionNarrative = _recessionNarrative.asStateFlow()

    // ── Countdown target ──────────────────────────────────────────────────────

    private val _countdownTarget = MutableStateFlow(prefs.getLong(CACHE_TARGET, 0L))
    val countdownTarget = _countdownTarget.asStateFlow()

    private fun setCountdownMs(months: Float) {
        if (months <= 0f) return
        val targetMs = System.currentTimeMillis() + (months * 30.44 * 86_400_000.0).toLong()
        prefs.edit().putLong(CACHE_TARGET, targetMs).apply()
        _countdownTarget.value = targetMs
    }

    fun updateCountdownTarget(regime: String?, narrativeText: String? = null) {
        val parsedMonths = if (narrativeText != null) {
            val m = Regex("""(\d+)[–\-](\d+)\s*months?""", RegexOption.IGNORE_CASE).find(narrativeText)
            if (m != null)
                ((m.groupValues[1].toFloatOrNull() ?: 0f) + (m.groupValues[2].toFloatOrNull() ?: 0f)) / 2f
            else null
        } else null
        val months = parsedMonths ?: when (regime) {
            "CRITICAL" -> 9f
            "WARNING"  -> 15f
            "CAUTION"  -> 21f
            else       -> 0f
        }
        if (months > 0f) setCountdownMs(months)
    }

    // ── PULSE ─────────────────────────────────────────────────────────────────

    private val _pulseResult          = MutableStateFlow<PulseResult?>(null)
    private val _pulseNarrativeState  = MutableStateFlow(PulseNarrativeState())
    private val _pulseLoading         = MutableStateFlow(false)

    val pulseResult         = _pulseResult.asStateFlow()
    val pulseNarrativeState = _pulseNarrativeState.asStateFlow()
    val pulseLoading        = _pulseLoading.asStateFlow()

    fun refreshPulse() {
        val fredKey   = _fredKey.value
        val geminiKey = _geminiKey.value
        if (fredKey.isBlank()) {
            viewModelScope.launch { _message.emit("Enter your FRED API key in Settings first.") }
            return
        }
        if (_pulseLoading.value) return

        viewModelScope.launch(Dispatchers.IO) {
            _pulseLoading.value = true
            try {
                val result = PulseEngine.compute(fredKey)
                if (result != null) {
                    _pulseResult.value = result
                    savePulseCache(result)

                    // Gemini narrative — always auto-fetched in parallel
                    launch {
                        if (geminiKey.isBlank()) {
                            _pulseNarrativeState.value = PulseNarrativeState(
                                status = GeminiPulseStatus.NO_KEY)
                            return@launch
                        }
                        _pulseNarrativeState.value = PulseNarrativeState(
                            status = GeminiPulseStatus.LOADING, loading = true)
                        val resp = try {
                            GeminiPulseClient.fetchNarrative(result, geminiKey)
                        } catch (_: Throwable) {
                            GeminiPulseClient.GeminiPulseResponse(null)
                        }
                        _pulseNarrativeState.value = when {
                            resp.text != null      -> PulseNarrativeState(
                                text = resp.text, status = GeminiPulseStatus.OK)
                            resp.errorCode == 429  -> PulseNarrativeState(
                                status = GeminiPulseStatus.QUOTA)
                            else                   -> PulseNarrativeState(
                                status = GeminiPulseStatus.ERROR)
                        }
                    }
                } else {
                    _message.emit("No PULSE data — check your FRED API key.")
                }
            } catch (e: Exception) {
                _message.emit("Network error: ${e.message}")
            } finally {
                _pulseLoading.value = false
            }
        }
    }

    // ── HPulse ────────────────────────────────────────────────────────────────

    private val _hPulseResult         = MutableStateFlow<HPulseResult?>(null)
    private val _hPulseNarrativeState = MutableStateFlow(HPulseNarrativeState())
    private val _hPulseLoading        = MutableStateFlow(false)

    val hPulseResult         = _hPulseResult.asStateFlow()
    val hPulseNarrativeState = _hPulseNarrativeState.asStateFlow()
    val hPulseLoading        = _hPulseLoading.asStateFlow()

    fun refreshHPulse() {
        val fredKey   = _fredKey.value
        val geminiKey = _geminiKey.value
        if (fredKey.isBlank()) {
            viewModelScope.launch { _message.emit("Enter your FRED API key in Settings first.") }
            return
        }
        if (_hPulseLoading.value) return

        viewModelScope.launch(Dispatchers.IO) {
            _hPulseLoading.value = true
            try {
                val result = HPulseEngine.compute(fredKey)
                if (result != null) {
                    _hPulseResult.value = result
                    saveHPulseCache(result)

                    // Gemini narrative — always auto-fetched in parallel
                    launch {
                        if (geminiKey.isBlank()) {
                            _hPulseNarrativeState.value = HPulseNarrativeState(
                                status = GeminiHPulseStatus.NO_KEY)
                            return@launch
                        }
                        _hPulseNarrativeState.value = HPulseNarrativeState(
                            status = GeminiHPulseStatus.LOADING, loading = true)
                        val resp = try {
                            GeminiHPulseClient.fetchNarrative(result, geminiKey)
                        } catch (_: Throwable) {
                            GeminiHPulseClient.GeminiHPulseResponse(null)
                        }
                        _hPulseNarrativeState.value = when {
                            resp.text != null     -> HPulseNarrativeState(
                                text = resp.text, status = GeminiHPulseStatus.OK)
                            resp.errorCode == 429 -> HPulseNarrativeState(
                                status = GeminiHPulseStatus.QUOTA)
                            else                  -> HPulseNarrativeState(
                                status = GeminiHPulseStatus.ERROR)
                        }
                    }
                } else {
                    _message.emit("No HPulse data — check your FRED API key.")
                }
            } catch (e: Exception) {
                _message.emit("Network error: ${e.message}")
            } finally {
                _hPulseLoading.value = false
            }
        }
    }

    // ── 30-Year export ────────────────────────────────────────────────────────

    private val _exportLoading = MutableStateFlow(false)
    val exportLoading = _exportLoading.asStateFlow()

    fun exportAll30Year() {
        val key = _fredKey.value
        if (key.isBlank()) {
            viewModelScope.launch { _message.emit("Enter your FRED API key in Settings first.") }
            return
        }
        if (_exportLoading.value) return
        val ctx = getApplication<Application>()

        viewModelScope.launch(Dispatchers.IO) {
            _exportLoading.value = true
            var ok = 0; var fail = 0
            fun track(path: String?) { if (path != null) ok++ else fail++ }
            try {
                _message.emit("Fetching 30-year data for all 7 indices… (~60–90s)")

                val fedHistory    = runCatching { FedEngine.computeHistory(key) }.getOrNull()
                val liimsiHistory = runCatching { InflationLeadingEngine.computeHistory(key) }.getOrNull()
                val llmsiHistory  = runCatching { LaborLeadingEngine.computeHistory(key) }.getOrNull()
                val rriHistory    = runCatching { RecessionEngine.computeHistory(key) }.getOrNull()
                val pulseHistory  = runCatching { PulseEngine.computeHistory(key) }.getOrNull()
                val hpulseHistory = runCatching { HPulseEngine.computeHistory(key) }.getOrNull()

                val isiHistory = fedHistory?.isi
                val lsiHistory = fedHistory?.lsi

                isiHistory?.let    { track(ChartExporter.exportIsiHistory(ctx, it,
                    _isiResult.value?.regime ?: "ANCHORED")) }
                lsiHistory?.let    { track(ChartExporter.exportLsiHistory(ctx, it,
                    _lsiResult.value?.regime ?: "MODERATE")) }
                liimsiHistory?.let { track(ChartExporter.exportLiimsiHistory(ctx, it,
                    _liimsiResult.value?.regime ?: "ANCHORED")) }
                llmsiHistory?.let  { track(ChartExporter.exportLlmsiHistory(ctx, it,
                    _llmsiResult.value?.regime ?: "MODERATE")) }
                rriHistory?.let    { track(ChartExporter.exportLongHistory(ctx,
                    title    = "Recession Risk Index (RRI)  —  30-Year History with NBER Recessions",
                    history  = it,
                    regime   = _rriResult.value?.regime ?: "STABLE",
                    fileName = "fed_rri_30yr.png")) }
                pulseHistory?.let  { track(ChartExporter.exportPulseHistory(ctx, it,
                    _pulseResult.value?.regime ?: "STABLE")) }
                hpulseHistory?.let { track(ChartExporter.exportHPulseHistory(ctx, it,
                    _hPulseResult.value?.band ?: "WARMING")) }

                track(ChartExporter.exportAllHistoryCsv(
                    context = ctx,
                    isi     = isiHistory,
                    lsi     = lsiHistory,
                    liimsi  = liimsiHistory,
                    llmsi   = llmsiHistory,
                    rri     = rriHistory,
                    pulse   = pulseHistory,
                    hpulse  = hpulseHistory,
                ))

                _message.emit("Export done: $ok file${if (ok != 1) "s" else ""} saved to Downloads" +
                    if (fail > 0) "  ($fail failed)" else "")
            } catch (e: Exception) {
                _message.emit("Export error: ${e.message}")
            } finally {
                _exportLoading.value = false
            }
        }
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    private val _message = MutableSharedFlow<String>()
    val message = _message.asSharedFlow()

    // ── Init: restore last cached state ──────────────────────────────────────

    init {
        loadCachedState()
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun refresh() {
        val key = _fredKey.value
        if (key.isBlank()) {
            viewModelScope.launch { _message.emit("Enter your FRED API key in Settings first.") }
            return
        }
        if (_loading.value) return

        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            try {
                val fed = FedEngine.compute(key)
                _isiResult.value    = fed.isi
                _lsiResult.value    = fed.lsi
                _liimsiResult.value = InflationLeadingEngine.compute(key)
                _llmsiResult.value  = LaborLeadingEngine.compute(key)
                _rriResult.value    = RecessionEngine.compute(key)

                if (fed.isi == null && fed.lsi == null) {
                    _message.emit("No data returned — check your FRED API key.")
                } else {
                    saveCachedState()
                    updateCountdownTarget(_rriResult.value?.regime)
                }
            } catch (e: Exception) {
                _message.emit("Network error: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    fun fetchRecessionNarrative() {
        val gemini = _geminiKey.value
        if (gemini.isBlank()) {
            viewModelScope.launch { _message.emit("Add a Gemini API key in Settings for AI analysis.") }
            return
        }
        val rri = _rriResult.value ?: run {
            viewModelScope.launch { _message.emit("Fetch recession data first.") }
            return
        }
        if (_geminiLoading.value) return

        viewModelScope.launch(Dispatchers.IO) {
            _geminiLoading.value = true
            var analysisText: String? = null
            try {
                analysisText = GeminiClient.fetchRecessionNarrative(
                    rriScore      = rri.current,
                    rriRegime     = rri.regime,
                    lastDataMonth = rri.lastDataMonth,
                    components    = rri.components,
                    trajectory    = rri.points,
                    apiKey        = gemini,
                )
            } catch (_: Throwable) {
                /* GeminiClient already returns null on error; this is a safety net */
            } finally {
                _recessionNarrative.value = analysisText
                    ?: "Could not fetch AI analysis — check Gemini key and internet connection."
                _geminiLoading.value = false
            }
            if (analysisText != null) updateCountdownTarget(_rriResult.value?.regime, analysisText)
            runCatching { if (analysisText != null) postAnalysisNotification() }
        }
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    private fun saveCachedState() {
        prefs.edit()
            .putString(CACHE_ISI,    gson.toJson(_isiResult.value))
            .putString(CACHE_LSI,    gson.toJson(_lsiResult.value))
            .putString(CACHE_LIIMSI, gson.toJson(_liimsiResult.value))
            .putString(CACHE_LLMSI,  gson.toJson(_llmsiResult.value))
            .putString(CACHE_RRI,    gson.toJson(_rriResult.value))
            .apply()
    }

    private fun savePulseCache(result: PulseResult) {
        prefs.edit().putString(CACHE_PULSE, gson.toJson(result)).apply()
    }

    private fun saveHPulseCache(result: HPulseResult) {
        prefs.edit().putString(CACHE_HPULSE, gson.toJson(result)).apply()
    }

    private fun loadCachedState() {
        runCatching {
            _isiResult.value    = loadJson(CACHE_ISI,    RegularResult::class.java)
            _lsiResult.value    = loadJson(CACHE_LSI,    RegularResult::class.java)
            _liimsiResult.value = loadJson(CACHE_LIIMSI, LeadingResult::class.java)
            _llmsiResult.value  = loadJson(CACHE_LLMSI,  LeadingResult::class.java)
            _rriResult.value    = loadJson(CACHE_RRI,    LeadingResult::class.java)
            _pulseResult.value  = loadJson(CACHE_PULSE,   PulseResult::class.java)
            _hPulseResult.value = loadJson(CACHE_HPULSE,  HPulseResult::class.java)
        }
    }

    private fun <T> loadJson(key: String, type: Class<T>): T? =
        prefs.getString(key, null)?.let { runCatching { gson.fromJson(it, type) }.getOrNull() }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun postAnalysisNotification() {
        val ctx = getApplication<Application>()
        val nm  = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI Analysis", NotificationManager.IMPORTANCE_DEFAULT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        try {
            nm.notify(NOTIF_ID,
                NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Fed Dashboard")
                    .setContentText("AI recession analysis is ready — tap clock to view")
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: SecurityException) { /* permission denied */ }
    }

    companion object {
        private const val KEY_FRED    = "fred_api_key"
        private const val KEY_GEMINI  = "gemini_api_key"
        private const val CACHE_ISI   = "cache_isi"
        private const val CACHE_LSI   = "cache_lsi"
        private const val CACHE_LIIMSI= "cache_liimsi"
        private const val CACHE_LLMSI = "cache_llmsi"
        private const val CACHE_RRI    = "cache_rri"
        private const val CACHE_PULSE   = "cache_pulse"
        private const val CACHE_HPULSE  = "cache_hpulse"
        private const val CACHE_TARGET  = "cache_countdown_target"
        private const val CHANNEL_ID   = "rri_analysis"
        private const val NOTIF_ID    = 1
    }
}
