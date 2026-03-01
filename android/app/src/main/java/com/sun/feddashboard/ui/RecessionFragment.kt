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
import com.sun.feddashboard.databinding.FragmentRecessionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecessionFragment : Fragment() {

    private var _binding: FragmentRecessionBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRecessionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.swipeRefresh.setOnRefreshListener { vm.refresh() }
        binding.btnRefresh.setOnClickListener { vm.refresh() }
        binding.btnInfo.setOnClickListener { showInfo() }
        binding.btnDownloadHD.setOnClickListener { exportHD() }
        binding.btnAiAnalysis.setOnClickListener { vm.fetchRecessionNarrative() }

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
                    }
                }
                launch {
                    vm.geminiLoading.collect { loading ->
                        binding.btnAiAnalysis.isEnabled = !loading
                        binding.progressGemini.visibility = if (loading) View.VISIBLE else View.GONE
                        if (loading) binding.tvNarrative.text = "Fetching AI analysis…"
                    }
                }
                launch {
                    vm.recessionNarrative.collect { text ->
                        if (text != null) {
                            binding.tvNarrative.text = text
                            binding.cardNarrative.visibility = View.VISIBLE
                        }
                    }
                }
                launch {
                    vm.message.collect { Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (vm.rriResult.value == null && !vm.loading.value) vm.refresh()
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

    private fun showInfo() {
        val rri = vm.rriResult.value
        val msg = buildString {
            appendLine("RECESSION RISK INDEX (RRI)")
            appendLine("Composite of 6 FRED leading indicators designed to")
            appendLine("predict recessions 6–18 months ahead.")
            appendLine()
            appendLine("REGIME GUIDE")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("LOW RISK  (≥ +0.5)  Expansion — low recession risk")
            appendLine("STABLE    (≥  0.0)  Moderate growth, monitoring")
            appendLine("CAUTION   (≥ -0.5)  Warning signs building")
            appendLine("WARNING   (≥ -1.0)  Elevated risk — history shows ~70% hit")
            appendLine("CRITICAL  (< -1.0)  Recession likely within 6–12 months")
            appendLine()
            appendLine("COMPONENTS")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("10Y−2Y Yield Spread    25%  (↓inv)  Gold standard predictor")
            appendLine("10Y−3M Yield Spread    20%  (↓inv)  Confirms inversion")
            appendLine("HY Credit Spread       20%  (↓inv)  Financial stress signal")
            appendLine("Building Permits YoY   15%          Housing leads economy")
            appendLine("Initial Claims YoY     10%  (↓inv)  Early labor stress")
            appendLine("UMich Consumer Sent.   10%          Confidence leads spending")
            appendLine("(↓inv) = inverted: positive z = lower recession risk")
            if (!rri?.components.isNullOrEmpty()) {
                appendLine()
                appendLine("CURRENT READINGS  —  ${rri?.lastDataMonth ?: "—"}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                rri?.components?.forEach { c ->
                    appendLine("%-22s  %+.2f  %+.3f".format(c.label.take(22), c.zScore, c.contribution))
                }
                val tot = rri?.components?.sumOf { it.weight } ?: 0.0
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

    private fun regimeDesc(regime: String) = when (regime) {
        "LOW RISK" -> "Expansion — low recession risk"
        "STABLE"   -> "Moderate growth, no immediate concern"
        "CAUTION"  -> "Warning signs — leading indicators softening"
        "WARNING"  -> "Elevated risk — historical recession probability ~70%"
        "CRITICAL" -> "Recession likely within 6–12 months"
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

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
