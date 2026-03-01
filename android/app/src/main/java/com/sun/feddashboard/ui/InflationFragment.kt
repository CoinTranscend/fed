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
import com.sun.feddashboard.databinding.FragmentInflationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InflationFragment : Fragment() {

    private var _binding: FragmentInflationBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInflationBinding.inflate(inflater, container, false)
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
                    vm.isiResult.collect { isi ->
                        if (isi != null) {
                            binding.chart.regularPoints = isi.points
                            binding.chart.regularRegime = isi.regime
                            val liimsi = vm.liimsiResult.value
                            binding.tvScore.text = buildScore("ISI", isi.regime, isi.current, "LIIMSI", liimsi?.regime, liimsi?.current)
                            binding.tvScore.setTextColor(regimeColor(isi.regime))
                            binding.tvUpdated.text = "Updated ${isi.updatedAt}  ·  ${isi.lastDataMonth}"
                        } else {
                            binding.tvScore.text = "No data — tap ↻"
                            binding.tvScore.setTextColor(Color.parseColor("#4A6680"))
                            binding.tvUpdated.text = ""
                        }
                    }
                }
                launch {
                    vm.liimsiResult.collect { liimsi ->
                        if (liimsi != null) {
                            binding.chart.leadingPoints = liimsi.points
                            binding.chart.leadingRegime = liimsi.regime
                            vm.isiResult.value?.let { isi ->
                                binding.tvScore.text = buildScore("ISI", isi.regime, isi.current, "LIIMSI", liimsi.regime, liimsi.current)
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
        val isi    = vm.isiResult.value
        val liimsi = vm.liimsiResult.value
        if (isi == null && liimsi == null) {
            Snackbar.make(binding.root, "No data yet — fetch first.", Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.btnDownloadHD.isEnabled = false
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                ChartExporter.exportSingle(
                    ctx,
                    title      = "Inflation  —  ISI (coincident) vs LIIMSI (leading)",
                    regular    = isi?.points ?: emptyList(),
                    leading    = liimsi?.points ?: emptyList(),
                    regRegime  = isi?.regime ?: "ANCHORED",
                    leadRegime = liimsi?.regime ?: "ANCHORED",
                    regScore   = isi?.current,
                    leadScore  = liimsi?.current,
                    fileName   = "fed_inflation_HD.png",
                )
            }
            Snackbar.make(binding.root,
                if (path != null) "Saved to $path" else "Export failed.",
                Snackbar.LENGTH_LONG).show()
            binding.btnDownloadHD.isEnabled = true
        }
    }

    private fun showInfo() {
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
        "COOLING"      -> Color.parseColor("#66BB6A")
        "ANCHORED"     -> Color.parseColor("#00BFA5")
        "RISING"       -> Color.parseColor("#FFC107")
        "ELEVATED"     -> Color.parseColor("#FF7043")
        "DEFLATIONARY" -> Color.parseColor("#5C6BC0")
        else           -> Color.parseColor("#00BFA5")
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
