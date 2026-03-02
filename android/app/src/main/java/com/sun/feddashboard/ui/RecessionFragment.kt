package com.sun.feddashboard.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.sun.feddashboard.MainViewModel
import com.sun.feddashboard.databinding.FragmentRecessionBinding
import com.sun.feddashboard.domain.RecessionEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecessionFragment : Fragment() {

    private var _binding: FragmentRecessionBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private var countdownJob: Job? = null

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification posted from ViewModel when granted */ }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRecessionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.swipeRefresh.setOnRefreshListener { vm.refresh() }
        binding.btnRefresh.setOnClickListener { vm.refresh() }
        binding.btnInfo.setOnClickListener { showInfo() }
        binding.btnDownloadHD.setOnClickListener { exportHD() }
        binding.btnDownload30Y.setOnClickListener { export30YChart() }

        // AI Analysis button: fetch if no narrative yet, or show popup if already fetched
        binding.btnAiAnalysis.setOnClickListener {
            if (vm.recessionNarrative.value != null && !vm.geminiLoading.value) {
                showAnalysisPopup()
            } else {
                vm.fetchRecessionNarrative()
            }
        }

        // Clock terminal: tap to view full AI analysis popup
        binding.clockContainer.setOnClickListener { showAnalysisPopup() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.loading.collect { loading ->
                        binding.swipeRefresh.isRefreshing = loading
                        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                        binding.btnRefresh.isEnabled = !loading
                    }
                }
                launch {
                    vm.rriResult.collect { rri ->
                        if (rri != null) {
                            binding.chart.regularPoints = rri.points
                            binding.chart.regularRegime = rri.regime
                            binding.chart.leadingPoints = emptyList()
                            binding.tvScore.text = "RRI: ${rri.regime}  ${"%.2f".format(rri.current)}"
                            binding.tvScore.setTextColor(regimeColor(rri.regime))
                            binding.tvRegimeDesc.text = regimeDesc(rri.regime)
                            binding.tvRegimeDesc.setTextColor(regimeColor(rri.regime))
                            binding.tvUpdated.text = "Updated ${rri.updatedAt}  ·  ${rri.lastDataMonth}"
                        } else {
                            binding.tvScore.text = "No data — tap ↻"
                            binding.tvScore.setTextColor(Color.parseColor("#4A6680"))
                            binding.tvRegimeDesc.text = ""
                            binding.tvUpdated.text = ""
                        }
                        updateCountdown()
                    }
                }
                launch {
                    vm.geminiLoading.collect { loading ->
                        binding.btnAiAnalysis.isEnabled = !loading
                        binding.btnAiAnalysis.text = if (loading) "Analysing..." else "✦ AI Analysis"
                    }
                }
                launch {
                    vm.recessionNarrative.collect { text ->
                        if (text != null) updateCountdown()
                    }
                }
                launch {
                    vm.countdownTarget.collect { targetMs ->
                        startCountdown(targetMs)
                    }
                }
                launch {
                    vm.message.collect { Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show() }
                }
            }
        }

        // Start countdown from cached target immediately (in case it was already saved)
        startCountdown(vm.countdownTarget.value)
    }

    // ── Countdown timer ────────────────────────────────────────────────────────

    private fun startCountdown(targetMs: Long) {
        countdownJob?.cancel()
        if (targetMs <= 0L) {
            _binding?.tvClockTime?.text = "---:--"
            return
        }
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            var colonOn = true
            while (isActive) {
                val remaining = targetMs - System.currentTimeMillis()
                if (remaining <= 0L) {
                    binding.tvClockTime.text = "000:00"
                    break
                }
                val totalHrs = remaining / 3_600_000L
                val days = totalHrs / 24L
                val hrs  = totalHrs % 24L
                val sep  = if (colonOn) ":" else " "
                binding.tvClockTime.text = "%03d%s%02d".format(days, sep, hrs)
                colonOn = !colonOn
                delay(1000L)
            }
        }
    }

    // ── Countdown label ────────────────────────────────────────────────────────

    private fun updateCountdown() {
        val narrative = vm.recessionNarrative.value
        val regime    = vm.rriResult.value?.regime
        val parsed    = if (narrative != null) parseTimingFromText(narrative) else null
        val estimate  = parsed ?: regimeCountdown(regime)
        binding.tvCountdown.text = if (estimate.isNotEmpty()) "[ $estimate ]" else ""
        binding.tvCountdown.visibility = if (estimate.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun parseTimingFromText(text: String): String? {
        val m = Regex("""(\d+)[–\-](\d+)\s*months?""", RegexOption.IGNORE_CASE).find(text) ?: return null
        return "${m.groupValues[1]}–${m.groupValues[2]} months"
    }

    private fun regimeCountdown(regime: String?) = when (regime) {
        "CRITICAL" -> "~6–12 months"
        "WARNING"  -> "~12–18 months"
        "CAUTION"  -> "~18–24 months"
        "STABLE"   -> "No imminent signal"
        "LOW RISK" -> "No imminent signal"
        else       -> ""
    }

    // ── Analysis popup ────────────────────────────────────────────────────────

    private fun showAnalysisPopup() {
        val narrative = vm.recessionNarrative.value
        if (narrative == null) {
            if (vm.geminiLoading.value) {
                Snackbar.make(binding.root, "Analysis is running — please wait...", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Tap 'AI Analysis' to generate analysis first.", Snackbar.LENGTH_SHORT).show()
            }
            return
        }
        val scrollView = ScrollView(requireContext())
        val tv = TextView(requireContext()).apply {
            text = narrative
            textSize = 13f
            setTextColor(Color.parseColor("#212121"))
            setPadding(64, 32, 64, 32)
        }
        scrollView.addView(tv)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("AI Analysis  ·  ${vm.rriResult.value?.regime ?: "RRI"}")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    // ── Chart exports ─────────────────────────────────────────────────────────

    private fun export30YChart() {
        val fredKey = vm.loadFredKey()
        if (fredKey.isBlank()) {
            Snackbar.make(binding.root, "Add your FRED API key in Settings first.", Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.btnDownload30Y.isEnabled = false
        val ctx    = requireContext().applicationContext
        val regime = vm.rriResult.value?.regime ?: "STABLE"
        viewLifecycleOwner.lifecycleScope.launch {
            Snackbar.make(binding.root, "Fetching 30-year data... this takes ~30s", Snackbar.LENGTH_LONG).show()
            val path = withContext(Dispatchers.IO) {
                val history = RecessionEngine.computeHistory(fredKey)
                if (history == null) return@withContext null
                ChartExporter.exportLongHistory(
                    ctx,
                    title    = "Recession Risk Index (RRI)  —  30-Year History with NBER Recessions",
                    history  = history,
                    regime   = regime,
                    fileName = "fed_rri_30year.png",
                )
            }
            Snackbar.make(binding.root,
                if (path != null) "Saved to $path" else "Export failed — check FRED key.",
                Snackbar.LENGTH_LONG).show()
            binding.btnDownload30Y.isEnabled = true
        }
    }

    private fun exportHD() {
        val rri = vm.rriResult.value
        if (rri == null) {
            Snackbar.make(binding.root, "No data yet — fetch first.", Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.btnDownloadHD.isEnabled = false
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                ChartExporter.exportSingle(
                    ctx,
                    title      = "Recession Risk Index (RRI)  —  Leading Composite",
                    regular    = rri.points,
                    leading    = emptyList(),
                    regRegime  = rri.regime,
                    leadRegime = rri.regime,
                    regScore   = rri.current,
                    leadScore  = null,
                    fileName   = "fed_recession_HD.png",
                )
            }
            Snackbar.make(binding.root,
                if (path != null) "Saved to $path" else "Export failed.",
                Snackbar.LENGTH_LONG).show()
            binding.btnDownloadHD.isEnabled = true
        }
    }

    // ── Info dialog ───────────────────────────────────────────────────────────

    private fun showInfo() {
        val rri = vm.rriResult.value
        val msg = buildString {
            appendLine("RECESSION RISK INDEX (RRI)")
            appendLine("Composite of 8 FRED leading indicators designed to")
            appendLine("predict recessions 6-18 months ahead.")
            appendLine()
            appendLine("REGIME GUIDE")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("LOW RISK  (>= +0.5)  Expansion — low recession risk")
            appendLine("STABLE    (>=  0.0)  Moderate growth, monitoring")
            appendLine("CAUTION   (>= -0.5)  Warning signs building")
            appendLine("WARNING   (>= -1.0)  Elevated risk — history shows ~70% hit")
            appendLine("CRITICAL  (< -1.0)   Recession likely within 6-12 months")
            appendLine()
            appendLine("COMPONENTS")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("10Y-2Y Yield Spread    20%  (inv)  Yield curve: top predictor")
            appendLine("10Y-3M Yield Spread    15%  (inv)  Confirms inversion signal")
            appendLine("HY Credit Spread       15%  (inv)  Financial stress signal")
            appendLine("Building Permits YoY   15%         Housing leads economy 6-12m")
            appendLine("Initial Claims YoY     10%  (inv)  Early labor stress")
            appendLine("Consumer Sentiment     10%         Confidence leads spending")
            appendLine("Copper/Gold Ratio      10%         Cross-asset growth proxy")
            appendLine("Real M2 Growth YoY      5%         Real money supply signal")
            appendLine("(inv) = inverted: higher raw value = higher recession risk")
            if (!rri?.components.isNullOrEmpty()) {
                appendLine()
                appendLine("CURRENT READINGS  —  ${rri?.lastDataMonth ?: "—"}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                rri?.components?.forEach { c ->
                    appendLine("%-22s  %+.2f  %+.3f".format(c.label.take(22), c.zScore, c.contribution))
                }
                val tot  = rri?.components?.sumOf { it.weight } ?: 0.0
                val comp = if (tot > 0) rri?.components?.sumOf { it.contribution }?.div(tot) ?: 0.0 else 0.0
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                append("Composite = %+.2f".format(comp))
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Recession Risk Index  ·  ${rri?.regime ?: "—"}")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun regimeDesc(regime: String) = when (regime) {
        "LOW RISK" -> "Expansion — low recession risk"
        "STABLE"   -> "Moderate growth, no immediate concern"
        "CAUTION"  -> "Warning signs — leading indicators softening"
        "WARNING"  -> "Elevated risk — historical recession probability ~70%"
        "CRITICAL" -> "Recession likely within 6-12 months"
        else       -> ""
    }

    private fun regimeColor(r: String) = when (r) {
        "LOW RISK" -> Color.parseColor("#66BB6A")
        "STABLE"   -> Color.parseColor("#00BFA5")
        "CAUTION"  -> Color.parseColor("#FFC107")
        "WARNING"  -> Color.parseColor("#FF7043")
        "CRITICAL" -> Color.parseColor("#EF5350")
        else       -> Color.parseColor("#00BFA5")
    }

    override fun onDestroyView() {
        countdownJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
