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
import com.sun.feddashboard.databinding.FragmentFedCompareBinding
import com.sun.feddashboard.model.ComponentReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FedCompareFragment : Fragment() {

    private var _binding: FragmentFedCompareBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFedCompareBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.swipeRefresh.setOnRefreshListener { vm.refresh() }
        binding.btnRefresh.setOnClickListener { vm.refresh() }
        binding.btnInflationInfo.setOnClickListener { showInflationInfo() }
        binding.btnLaborInfo.setOnClickListener { showLaborInfo() }
        binding.btnInflationHD.setOnClickListener { exportInflationHD() }
        binding.btnLaborHD.setOnClickListener { exportLaborHD() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    vm.loading.collect { loading ->
                        binding.swipeRefresh.isRefreshing = loading
                        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                        binding.btnRefresh.isEnabled = !loading
                    }
                }

                // ── Inflation ──────────────────────────────────────────────
                launch {
                    vm.isiResult.collect { isi ->
                        if (isi != null) {
                            val liimsi = vm.liimsiResult.value
                            binding.inflationChart.regularPoints = isi.points
                            binding.inflationChart.regularRegime = isi.regime
                            binding.tvInflationScore.text = buildScore(
                                "ISI", isi.regime, isi.current,
                                "LIIMSI", liimsi?.regime, liimsi?.current)
                            binding.tvInflationScore.setTextColor(inflationColor(isi.regime))
                            binding.tvInflationUpdated.text =
                                "Updated ${isi.updatedAt}  ·  ${isi.lastDataMonth}"
                        } else {
                            binding.tvInflationScore.text = "No data — tap ↻"
                            binding.tvInflationScore.setTextColor(Color.parseColor("#4A6680"))
                            binding.tvInflationUpdated.text = ""
                        }
                    }
                }
                launch {
                    vm.liimsiResult.collect { liimsi ->
                        if (liimsi != null) {
                            binding.inflationChart.leadingPoints = liimsi.points
                            binding.inflationChart.leadingRegime = liimsi.regime
                            vm.isiResult.value?.let { isi ->
                                binding.tvInflationScore.text = buildScore(
                                    "ISI", isi.regime, isi.current,
                                    "LIIMSI", liimsi.regime, liimsi.current)
                            }
                        }
                    }
                }

                // ── Labor ──────────────────────────────────────────────────
                launch {
                    vm.lsiResult.collect { lsi ->
                        if (lsi != null) {
                            val llmsi = vm.llmsiResult.value
                            binding.laborChart.regularPoints = lsi.points
                            binding.laborChart.regularRegime = lsi.regime
                            binding.tvLaborScore.text = buildScore(
                                "LSI", lsi.regime, lsi.current,
                                "LLMSI", llmsi?.regime, llmsi?.current)
                            binding.tvLaborScore.setTextColor(laborColor(lsi.regime))
                            binding.tvLaborUpdated.text =
                                "Updated ${lsi.updatedAt}  ·  ${lsi.lastDataMonth}"
                        } else {
                            binding.tvLaborScore.text = "No data — tap ↻"
                            binding.tvLaborScore.setTextColor(Color.parseColor("#4A6680"))
                            binding.tvLaborUpdated.text = ""
                        }
                    }
                }
                launch {
                    vm.llmsiResult.collect { llmsi ->
                        if (llmsi != null) {
                            binding.laborChart.leadingPoints = llmsi.points
                            binding.laborChart.leadingRegime = llmsi.regime
                            vm.lsiResult.value?.let { lsi ->
                                binding.tvLaborScore.text = buildScore(
                                    "LSI", lsi.regime, lsi.current,
                                    "LLMSI", llmsi.regime, llmsi.current)
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

    // ── Score line ─────────────────────────────────────────────────────────────

    private fun buildScore(
        rL: String, rR: String, rS: Float,
        lL: String, lR: String?, lS: Float?,
    ): String {
        val reg = "$rL: $rR ${"%.2f".format(rS)}"
        return if (lR != null && lS != null) "$reg   →  $lL: $lR ${"%.2f".format(lS)}" else reg
    }

    // ── HD exports ────────────────────────────────────────────────────────────

    private fun exportInflationHD() {
        val isi    = vm.isiResult.value
        val liimsi = vm.liimsiResult.value
        if (isi == null && liimsi == null) {
            Snackbar.make(binding.root, "No data yet — fetch first.", Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.btnInflationHD.isEnabled = false
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                ChartExporter.exportSingle(ctx,
                    title      = "Inflation  —  ISI (coincident) vs LIIMSI (leading)",
                    regular    = isi?.points ?: emptyList(),
                    leading    = liimsi?.points ?: emptyList(),
                    regRegime  = isi?.regime ?: "ANCHORED",
                    leadRegime = liimsi?.regime ?: "ANCHORED",
                    regScore   = isi?.current,
                    leadScore  = liimsi?.current,
                    fileName   = "fed_inflation_HD.png")
            }
            Snackbar.make(binding.root,
                if (path != null) "Saved to $path" else "Export failed.",
                Snackbar.LENGTH_LONG).show()
            binding.btnInflationHD.isEnabled = true
        }
    }

    private fun exportLaborHD() {
        val lsi   = vm.lsiResult.value
        val llmsi = vm.llmsiResult.value
        if (lsi == null && llmsi == null) {
            Snackbar.make(binding.root, "No data yet — fetch first.", Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.btnLaborHD.isEnabled = false
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                ChartExporter.exportSingle(ctx,
                    title      = "Labor Market  —  LSI (coincident) vs LLMSI (leading)",
                    regular    = lsi?.points ?: emptyList(),
                    leading    = llmsi?.points ?: emptyList(),
                    regRegime  = lsi?.regime ?: "MODERATE",
                    leadRegime = llmsi?.regime ?: "MODERATE",
                    regScore   = lsi?.current,
                    leadScore  = llmsi?.current,
                    fileName   = "fed_labor_HD.png")
            }
            Snackbar.make(binding.root,
                if (path != null) "Saved to $path" else "Export failed.",
                Snackbar.LENGTH_LONG).show()
            binding.btnLaborHD.isEnabled = true
        }
    }

    // ── Info dialogs ──────────────────────────────────────────────────────────

    private fun showInflationInfo() {
        val isi    = vm.isiResult.value
        val liimsi = vm.liimsiResult.value
        val msg = buildString {
            appendLine("REGIME GUIDE")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("ELEVATED     (≥ +0.5)  Inflation running hot")
            appendLine("RISING       (≥  0.0)  Inflation picking up")
            appendLine("ANCHORED     (≥ -0.5)  Near target — ideal")
            appendLine("COOLING      (≥ -1.0)  Inflation fading")
            appendLine("DEFLATIONARY (< -1.0)  Deflation risk")
            appendLine()
            appendLine("ISI — Coincident (10 equal-weight)")
            appendLine("Core CPI, Core PCE, Trimmed PCE, Sticky CPI,")
            appendLine("PPI Final Demand, PPI Commodities,")
            appendLine("5Y/10Y Breakevens, Shelter CPI, UMich Exp.")
            appendLine()
            appendLine("LIIMSI — Leading (7 weighted)")
            appendLine("5Y TIPS Breakeven 20%, PPI Final Demand 20%,")
            appendLine("Atlanta Flexible CPI 15%, UMich 15%,")
            appendLine("Avg Hourly Earnings 10%, PPI Commodities 10%,")
            appendLine("5Y/5Y Fwd Breakeven 10%.")
            if (!isi?.components.isNullOrEmpty()) {
                appendLine(); append(componentTable("ISI", isi?.lastDataMonth, isi?.components))
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Inflation  ·  ISI: ${isi?.regime ?: "—"}  |  LIIMSI: ${liimsi?.regime ?: "—"}")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLaborInfo() {
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

    private fun componentTable(name: String, month: String?, comps: List<ComponentReading>?): String {
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

    // ── Colors ────────────────────────────────────────────────────────────────

    private fun inflationColor(r: String) = when (r) {
        "COOLING"      -> Color.parseColor("#66BB6A")
        "ANCHORED"     -> Color.parseColor("#00BFA5")
        "RISING"       -> Color.parseColor("#FFC107")
        "ELEVATED"     -> Color.parseColor("#FF7043")
        "DEFLATIONARY" -> Color.parseColor("#5C6BC0")
        else           -> Color.parseColor("#00BFA5")
    }

    private fun laborColor(r: String) = when (r) {
        "STRONG"    -> Color.parseColor("#66BB6A")
        "MODERATE"  -> Color.parseColor("#00BFA5")
        "SOFTENING" -> Color.parseColor("#FFC107")
        "WEAK"      -> Color.parseColor("#FF7043")
        "CRITICAL"  -> Color.parseColor("#EF5350")
        else        -> Color.parseColor("#00BFA5")
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
