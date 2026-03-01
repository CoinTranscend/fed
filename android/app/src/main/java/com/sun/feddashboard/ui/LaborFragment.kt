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
import com.sun.feddashboard.databinding.FragmentLaborBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LaborFragment : Fragment() {

    private var _binding: FragmentLaborBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLaborBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.swipeRefresh.setOnRefreshListener { vm.refresh() }
        binding.btnRefresh.setOnClickListener { vm.refresh() }
        binding.btnInfo.setOnClickListener { showInfo() }
        binding.btnDownloadHD.setOnClickListener { exportHD() }

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
                    vm.lsiResult.collect { lsi ->
                        if (lsi != null) {
                            binding.chart.regularPoints = lsi.points
                            binding.chart.regularRegime = lsi.regime
                            val llmsi = vm.llmsiResult.value
                            binding.tvScore.text = buildScore("LSI", lsi.regime, lsi.current, "LLMSI", llmsi?.regime, llmsi?.current)
                            binding.tvScore.setTextColor(regimeColor(lsi.regime))
                            binding.tvUpdated.text = "Updated ${lsi.updatedAt}  ·  ${lsi.lastDataMonth}"
                        } else {
                            binding.tvScore.text = "No data — tap ↻"
                            binding.tvScore.setTextColor(Color.parseColor("#4A6680"))
                            binding.tvUpdated.text = ""
                        }
                    }
                }
                launch {
                    vm.llmsiResult.collect { llmsi ->
                        if (llmsi != null) {
                            binding.chart.leadingPoints = llmsi.points
                            binding.chart.leadingRegime = llmsi.regime
                            vm.lsiResult.value?.let { lsi ->
                                binding.tvScore.text = buildScore("LSI", lsi.regime, lsi.current, "LLMSI", llmsi.regime, llmsi.current)
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


    private fun buildScore(rL: String, rR: String, rS: Float, lL: String, lR: String?, lS: Float?): String {
        val reg = "$rL: $rR ${"%.2f".format(rS)}"
        return if (lR != null && lS != null) "$reg   →  $lL: $lR ${"%.2f".format(lS)}" else reg
    }

    private fun exportHD() {
        val lsi   = vm.lsiResult.value
        val llmsi = vm.llmsiResult.value
        if (lsi == null && llmsi == null) {
            Snackbar.make(binding.root, "No data yet — fetch first.", Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.btnDownloadHD.isEnabled = false
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                ChartExporter.exportSingle(
                    ctx,
                    title      = "Labor Market  —  LSI (coincident) vs LLMSI (leading)",
                    regular    = lsi?.points ?: emptyList(),
                    leading    = llmsi?.points ?: emptyList(),
                    regRegime  = lsi?.regime ?: "MODERATE",
                    leadRegime = llmsi?.regime ?: "MODERATE",
                    regScore   = lsi?.current,
                    leadScore  = llmsi?.current,
                    fileName   = "fed_labor_HD.png",
                )
            }
            Snackbar.make(binding.root,
                if (path != null) "Saved to $path" else "Export failed.",
                Snackbar.LENGTH_LONG).show()
            binding.btnDownloadHD.isEnabled = true
        }
    }

    private fun showInfo() {
        val lsi   = vm.lsiResult.value
        val llmsi = vm.llmsiResult.value
        val msg = buildString {
            appendLine("REGIME GUIDE")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("STRONG    (≥ +0.5)  Robust expansion")
            appendLine("MODERATE  (≥  0.0)  Healthy, moderating")
            appendLine("SOFTENING (≥ -0.5)  Early warning signs")
            appendLine("WEAK      (≥ -1.0)  Significant deterioration")
            appendLine("CRITICAL  (< -1.0)  Recession-level distress")
            appendLine()
            appendLine("LSI — Coincident (9 equal-weight)")
            appendLine("Nonfarm Payrolls YoY, Unemployment (↓inv),")
            appendLine("LFPR, Initial Claims (↓inv), Continuing Claims (↓inv),")
            appendLine("JOLTS Openings YoY, Quits Rate,")
            appendLine("Avg Hourly Earnings YoY, Mfg Employment YoY.")
            appendLine()
            appendLine("LLMSI — Leading (7 weighted, leads payrolls 2–6m)")
            appendLine("Initial Claims 20%(↓inv), Mfg Hours 15%,")
            appendLine("Temp Help 15%, JOLTS Openings 15%,")
            appendLine("Layoffs 15%(↓inv), Quits Rate 10%,")
            appendLine("Continuing Claims 10%(↓inv).")
            if (!lsi?.components.isNullOrEmpty()) {
                appendLine(); append(componentTable("LSI", lsi?.lastDataMonth, lsi?.components))
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Labor  ·  LSI: ${lsi?.regime ?: "—"}  |  LLMSI: ${llmsi?.regime ?: "—"}")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun componentTable(name: String, month: String?, comps: List<com.sun.feddashboard.model.ComponentReading>?): String {
        if (comps.isNullOrEmpty()) return ""
        return buildString {
            appendLine("$name  —  ${month ?: "—"}")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            comps.forEach { c ->
                appendLine("%-20s  %+.2f  %+.3f".format(c.label.take(20), c.zScore, c.contribution))
            }
            val tot = comps.sumOf { it.weight }
            val comp = if (tot > 0) comps.sumOf { it.contribution } / tot else 0.0
            append("Composite = %+.2f".format(comp))
        }
    }

    private fun regimeColor(r: String) = when (r) {
        "STRONG"    -> Color.parseColor("#66BB6A")
        "MODERATE"  -> Color.parseColor("#00BFA5")
        "SOFTENING" -> Color.parseColor("#FFC107")
        "WEAK"      -> Color.parseColor("#FF7043")
        "CRITICAL"  -> Color.parseColor("#EF5350")
        else        -> Color.parseColor("#00BFA5")
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
