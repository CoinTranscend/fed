package com.sun.feddashboard.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.sun.feddashboard.MainViewModel
import com.sun.feddashboard.databinding.FragmentPulseBinding
import com.sun.feddashboard.domain.PulseEngine
import com.sun.feddashboard.model.ComponentReading
import com.sun.feddashboard.model.GeminiPulseStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PulseFragment : Fragment() {

    private var _binding: FragmentPulseBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPulseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.swipeRefresh.setOnRefreshListener { vm.refreshPulse() }
        binding.btnRefresh.setOnClickListener { vm.refreshPulse() }
        binding.btnInfo.setOnClickListener { showInfo() }
        binding.btnDownloadHD.setOnClickListener { export30Yr() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    vm.pulseLoading.collect { loading ->
                        binding.swipeRefresh.isRefreshing = loading
                        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                        binding.btnRefresh.isEnabled = !loading
                    }
                }

                launch {
                    vm.pulseResult.collect { result ->
                        if (result != null) {
                            val color = regimeColor(result.regime)
                            binding.tvScore.text    = "PULSE: ${result.regime}  ${"%.2f".format(result.current)}"
                            binding.tvScore.setTextColor(color)
                            binding.tvRegimeDesc.text = regimeDesc(result.regime)
                            binding.tvRegimeDesc.setTextColor(color)
                            binding.tvUpdated.text = "Updated ${result.updatedAt}  ·  ${result.lastDataMonth}"

                            // Sub-scores
                            binding.layoutSubScores.visibility = View.VISIBLE
                            binding.tvEip.text = "%+.2f".format(result.eipScore)
                            binding.tvEip.setTextColor(subScoreColor(result.eipScore))
                            binding.tvAsi.text = "%+.2f".format(result.asiScore)
                            binding.tvAsi.setTextColor(subScoreColor(result.asiScore))
                            binding.tvFss.text = "%+.2f".format(result.fssScore)
                            binding.tvFss.setTextColor(subScoreColor(result.fssScore))

                            // Chart (single composite line, no leading overlay)
                            binding.chart.regularPoints = result.points
                            binding.chart.regularRegime = result.regime
                            binding.chart.leadingPoints = emptyList()
                        } else {
                            binding.tvScore.text = "No data — tap ↻"
                            binding.tvScore.setTextColor(Color.parseColor("#4A6680"))
                            binding.tvRegimeDesc.text = ""
                            binding.tvUpdated.text = ""
                            binding.layoutSubScores.visibility = View.GONE
                        }
                    }
                }

                launch {
                    vm.pulseNarrativeState.collect { state ->
                        when {
                            state.loading -> {
                                binding.cardNarrative.visibility = View.VISIBLE
                                binding.progressGemini.visibility = View.VISIBLE
                                binding.tvNarrative.text = state.statusMessage
                            }
                            state.text != null -> {
                                binding.cardNarrative.visibility = View.VISIBLE
                                binding.progressGemini.visibility = View.GONE
                                binding.tvNarrative.text = state.text
                            }
                            state.status != GeminiPulseStatus.IDLE -> {
                                binding.cardNarrative.visibility = View.VISIBLE
                                binding.progressGemini.visibility = View.GONE
                                binding.tvNarrative.text = state.statusMessage
                                binding.tvNarrative.setTextColor(Color.parseColor("#9E9E9E"))
                            }
                            else -> {
                                binding.cardNarrative.visibility = View.GONE
                            }
                        }
                    }
                }

                launch {
                    vm.message.collect { Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show() }
                }
            }
        }
    }

    // ── 30-Year Export ────────────────────────────────────────────────────────

    private fun export30Yr() {
        val regime = vm.pulseResult.value?.regime ?: "STABLE"
        val fredKey = vm.loadFredKey()
        if (fredKey.isBlank()) {
            Snackbar.make(binding.root, "Enter FRED API key in Settings first.", Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.btnDownloadHD.isEnabled = false
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            Snackbar.make(binding.root, "Fetching 30 years from FRED…", Snackbar.LENGTH_LONG).show()
            val path = withContext(Dispatchers.IO) {
                val history = PulseEngine.computeHistory(fredKey) ?: return@withContext null
                ChartExporter.exportPulseHistory(ctx, history, regime)
            }
            Snackbar.make(binding.root,
                if (path != null) "Saved to $path" else "Export failed — check connection.",
                Snackbar.LENGTH_LONG).show()
            binding.btnDownloadHD.isEnabled = true
        }
    }

    // ── Info dialog ───────────────────────────────────────────────────────────

    private fun showInfo() {
        val result = vm.pulseResult.value
        val msg = buildString {
            appendLine("PULSE — Consumer Stress Index")
            appendLine("Distributional inflation methodology (Fed / Hobijn & Lagakos).")
            appendLine("Positive = resilient households. Negative = consumer stress.")
            appendLine()
            appendLine("REGIME GUIDE")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("RESILIENT (≥ +0.5)  Wages outpacing essentials; savings healthy")
            appendLine("STABLE    (≥  0.0)  Mild squeeze, household buffers intact")
            appendLine("STRESSED  (≥ −0.5)  Working-class pinch; CC debt rising")
            appendLine("STRAINED  (≥ −1.0)  Lower income brackets in significant distress")
            appendLine("BREAKING  (< −1.0)  Systemic consumer stress; demand collapse risk")
            appendLine()
            appendLine("COMPOSITE = 0.40 × EIP + 0.35 × ASI + 0.25 × FSS")
            appendLine()
            appendLine("EIP — Essentials Inflation Pressure")
            appendLine("Real burden = -(item YoY − AHE YoY) weighted by CEX quintile shares.")
            appendLine("Democratic weighting: 50 % Q1 + 35 % Q2 + 15 % Q3.")
            appendLine("Shelter 42/37/32 %, Food 10/9/8 %, Gas 9/10/10 %,")
            appendLine("Eggs, Chicken, Milk (BLS Average Price series).")
            appendLine()
            appendLine("ASI — Affordability Squeeze Index")
            appendLine("CC Delinquency Rate 30 %, CC Balances YoY 25 %,")
            appendLine("Savings Rate 25 %, CC Charge-Off Rate 20 %.")
            appendLine()
            appendLine("FSS — Forward Stress Signal")
            appendLine("Consumer Sentiment 40 %, Food & Bev Retail YoY 30 %,")
            appendLine("Real Disposable Income YoY 30 %.")

            if (!result?.eipComponents.isNullOrEmpty()) {
                appendLine()
                appendLine("EIP COMPONENTS  —  ${result?.lastDataMonth ?: "—"}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                result?.eipComponents?.forEach { c ->
                    appendLine("%-22s  z=%+.2f  Q1=%.1f%%".format(c.label.take(22), c.zScore, c.weight * 100))
                }
            }
            if (!result?.asiComponents.isNullOrEmpty()) {
                appendLine()
                appendLine("ASI COMPONENTS")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                result?.asiComponents?.forEach { c -> appendLine(componentLine(c)) }
            }
            if (!result?.fssComponents.isNullOrEmpty()) {
                appendLine()
                appendLine("FSS COMPONENTS")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                result?.fssComponents?.forEach { c -> appendLine(componentLine(c)) }
            }

            val narrative = vm.pulseNarrativeState.value
            if (narrative.status == GeminiPulseStatus.NO_KEY || narrative.status == GeminiPulseStatus.IDLE) {
                appendLine()
                appendLine("AI NARRATIVE")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                append(narrative.statusMessage.ifEmpty { "Add a Gemini key in Settings for AI narrative." })
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("PULSE  ·  ${result?.regime ?: "—"}")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun componentLine(c: ComponentReading) =
        "%-22s  z=%+.2f  wt=%.0f%%".format(c.label.take(22), c.zScore, c.weight * 100)

    // ── Color helpers ─────────────────────────────────────────────────────────

    private fun regimeColor(r: String) = when (r) {
        "RESILIENT" -> Color.parseColor("#43A047")
        "STABLE"    -> Color.parseColor("#00897B")
        "STRESSED"  -> Color.parseColor("#F9A825")
        "STRAINED"  -> Color.parseColor("#E64A19")
        "BREAKING"  -> Color.parseColor("#C62828")
        else        -> Color.parseColor("#00897B")
    }

    private fun regimeDesc(r: String) = when (r) {
        "RESILIENT" -> "Wages outpacing essentials — household finances healthy"
        "STABLE"    -> "Mild squeeze — household buffers still intact"
        "STRESSED"  -> "Working-class pinch — credit card debt rising, savings falling"
        "STRAINED"  -> "Lower income brackets under significant distress"
        "BREAKING"  -> "Systemic consumer stress — demand collapse risk"
        else        -> ""
    }

    /** Sub-score chip color: teal = healthy, amber = caution, red = stress. */
    private fun subScoreColor(score: Float) = when {
        score >= 0.0f  -> Color.parseColor("#00897B")
        score >= -0.5f -> Color.parseColor("#F9A825")
        else           -> Color.parseColor("#E64A19")
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
