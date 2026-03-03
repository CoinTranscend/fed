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
import com.sun.feddashboard.databinding.FragmentHpulseBinding
import com.sun.feddashboard.domain.HPulseEngine
import com.sun.feddashboard.model.GeminiHPulseStatus
import com.sun.feddashboard.model.HPulseComponentScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HPulseFragment : Fragment() {

    private var _binding: FragmentHpulseBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHpulseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.swipeRefresh.setOnRefreshListener { vm.refreshHPulse() }
        binding.btnRefresh.setOnClickListener { vm.refreshHPulse() }
        binding.btnInfo.setOnClickListener { showInfo() }
        binding.btnDownloadHD.setOnClickListener { export30Yr() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    vm.hPulseLoading.collect { loading ->
                        binding.swipeRefresh.isRefreshing = loading
                        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                        binding.btnRefresh.isEnabled = !loading
                    }
                }

                launch {
                    vm.hPulseResult.collect { result ->
                        if (result != null) {
                            val bandColor = bandColor(result.band)

                            binding.tvCompositeScore.text =
                                "HPulse: ${"%.1f".format(result.composite)}  →  ${result.band}"
                            binding.tvCompositeScore.setTextColor(bandColor)

                            binding.tvBandDesc.text = bandDesc(result.band)
                            binding.tvBandDesc.setTextColor(bandColor)

                            binding.tvUpdated.text =
                                "Updated ${result.updatedAt}  ·  ${result.lastDataMonth}"

                            // Tier score cards
                            binding.layoutTierScores.visibility = View.VISIBLE

                            binding.tvBurnScore.text   = "%.1f".format(result.burnScore)
                            binding.tvMiddleScore.text = "%.1f".format(result.middleScore)
                            binding.tvBufferScore.text = "%.1f".format(result.bufferScore)

                            binding.tvBurnScore.setTextColor(scoreColor(result.burnScore))
                            binding.tvMiddleScore.setTextColor(scoreColor(result.middleScore))
                            binding.tvBufferScore.setTextColor(scoreColor(result.bufferScore))

                            // Chart
                            binding.hpulseChart.points = result.points

                        } else {
                            binding.tvCompositeScore.text = "No data — tap ↻"
                            binding.tvCompositeScore.setTextColor(Color.parseColor("#4A6680"))
                            binding.tvBandDesc.text = ""
                            binding.tvUpdated.text = ""
                            binding.layoutTierScores.visibility = View.GONE
                        }
                    }
                }

                launch {
                    vm.hPulseNarrativeState.collect { state ->
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
                            state.status != GeminiHPulseStatus.IDLE -> {
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
        val band = vm.hPulseResult.value?.band ?: "WARMING"
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
                val history = HPulseEngine.computeHistory(fredKey) ?: return@withContext null
                ChartExporter.exportHPulseHistory(ctx, history, band)
            }
            Snackbar.make(binding.root,
                if (path != null) "Saved to $path" else "Export failed — check connection.",
                Snackbar.LENGTH_LONG).show()
            binding.btnDownloadHD.isEnabled = true
        }
    }

    // ── Info dialog ───────────────────────────────────────────────────────────

    private fun showInfo() {
        val result = vm.hPulseResult.value
        val msg = buildString {
            appendLine("HPulse — Household Pulse Index")
            appendLine("0–100 stress scale: 100 = maximum household stress.")
            appendLine("Scores calibrated to historical CPI and debt extremes.")
            appendLine()
            appendLine("INCOME TIERS")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Burn Zone    ≤ \$65,100 /yr   — 59 % of US households")
            appendLine("Middle Pulse   \$65,101–\$105,500 — 27 %")
            appendLine("Buffer Zone  \$105,501–\$175,700 — 14 %")
            appendLine()
            appendLine("BAND GUIDE  (composite score)")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("STABLE   0–24   Essentials affordable relative to wages")
            appendLine("WARMING  25–49  Mild stress; disposable slack shrinking")
            appendLine("STRAINED 50–74  Significant squeeze; debt levels rising")
            appendLine("BURN     75–100 Crisis-level household stress")
            appendLine()
            appendLine("COMPOSITE = 0.59 × Burn + 0.27 × Middle + 0.14 × Buffer")
            appendLine()
            appendLine("ESSENTIALS PRESSURE  (real burden = price YoY − AHE YoY)")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Shelter anchor: 6% real burden → score 100")
            appendLine("Groceries anchor: 8 % real burden → score 100")
            appendLine("Gas anchor: 30% real burden range (centered at +15%)")
            appendLine("Utilities anchor: 10% real burden → score 100")
            appendLine()
            appendLine("Burn essentials weights:  Housing 68%, Grocery 15%, Gas 6%, Util 11%")
            appendLine("Middle essentials weights: Housing 64%, Grocery 16%, Gas 7%, Util 13%")
            appendLine("Buffer essentials weights: Housing 58%, Grocery 18%, Gas 9%, Util 15%")
            appendLine()
            appendLine("DEBT STRESS = 0.40 × CC Revolving YoY + 0.40 × Delinquency + 0.20 × Cushion")
            appendLine("CC Revolving anchor: 15% YoY growth → score 100")
            appendLine("Delinquency anchor:  1.5% → 0  |  7.0% → 100")
            appendLine("Cushion (savings):   ≥8% PSAVERT → 0  |  ≤2% → 100")

            if (!result?.essentialsComponents.isNullOrEmpty()) {
                appendLine()
                appendLine("ESSENTIALS  —  ${result?.lastDataMonth ?: "—"}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                result?.essentialsComponents?.forEach { c ->
                    appendLine(componentLine(c))
                }
            }
            if (!result?.debtComponents.isNullOrEmpty()) {
                appendLine()
                appendLine("DEBT STRESS  —  ${result?.lastDataMonth ?: "—"}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                result?.debtComponents?.forEach { c ->
                    appendLine(componentLine(c))
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("HPulse  ·  ${result?.band ?: "—"}  (${result?.let { "%.1f".format(it.composite) } ?: "—"}/100)")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun componentLine(c: HPulseComponentScore) =
        "%-22s  %5.1f/100  wt=%.0f%%".format(c.label.take(22), c.rawScore, c.weight * 100)

    // ── Color helpers ─────────────────────────────────────────────────────────

    /** Color for the overall band label. */
    private fun bandColor(band: String) = when (band) {
        "STABLE"   -> Color.parseColor("#43A047")
        "WARMING"  -> Color.parseColor("#F9A825")
        "STRAINED" -> Color.parseColor("#E64A19")
        "BURN"     -> Color.parseColor("#C62828")
        else       -> Color.parseColor("#00897B")
    }

    /** Color for an individual 0–100 tier score chip. */
    private fun scoreColor(score: Float) = when {
        score < 25f -> Color.parseColor("#43A047")
        score < 50f -> Color.parseColor("#F9A825")
        score < 75f -> Color.parseColor("#E64A19")
        else        -> Color.parseColor("#C62828")
    }

    private fun bandDesc(band: String) = when (band) {
        "STABLE"   -> "Essentials affordable relative to wages — households holding steady"
        "WARMING"  -> "Mild squeeze — disposable income slack beginning to shrink"
        "STRAINED" -> "Significant squeeze — credit card debt rising, savings falling"
        "BURN"     -> "Crisis-level household stress — demand destruction risk"
        else       -> ""
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
