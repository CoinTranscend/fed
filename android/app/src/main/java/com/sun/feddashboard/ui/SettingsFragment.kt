package com.sun.feddashboard.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.sun.feddashboard.MainViewModel
import com.sun.feddashboard.ThemePrefs
import com.sun.feddashboard.databinding.FragmentSettingsBinding
import com.sun.feddashboard.widget.FedWidgetDataStore
import com.sun.feddashboard.widget.FedWidgetProvider
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pkg = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        binding.tvVersion.text = "Fed Dashboard v${pkg.versionName}"

        val fredKey = vm.loadFredKey()
        if (fredKey.isNotBlank()) binding.etFredKey.setText(fredKey)

        val geminiKey = vm.loadGeminiKey()
        if (geminiKey.isNotBlank()) binding.etGeminiKey.setText(geminiKey)

        binding.btnSaveFredKey.setOnClickListener {
            val key = binding.etFredKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) { Snackbar.make(binding.root, "Enter a FRED API key.", Snackbar.LENGTH_SHORT).show(); return@setOnClickListener }
            vm.saveFredKey(key)
            Snackbar.make(binding.root, "FRED key saved.", Snackbar.LENGTH_SHORT).show()
        }

        binding.btnSaveGeminiKey.setOnClickListener {
            val key = binding.etGeminiKey.text?.toString()?.trim() ?: ""
            vm.saveGeminiKey(key)
            Snackbar.make(binding.root, if (key.isBlank()) "Gemini key cleared." else "Gemini key saved.", Snackbar.LENGTH_SHORT).show()
        }

        binding.tvFredHint.text = "Free key at fred.stlouisfed.org — used for all FRED economic data."
        binding.tvGeminiHint.text = "Optional — enables AI recession commentary on the Recession page. Get a key at aistudio.google.com."

        binding.btnExportAll.setOnClickListener { vm.exportAll30Year() }

        // ── Widget card ───────────────────────────────────────────────────────
        setupWidgetCard()

        val isNight = ThemePrefs.isNightMode(requireContext())
        binding.switchDarkMode.isChecked = isNight
        binding.tvThemeLabel.text = if (isNight) "Dark mode" else "Light mode"
        binding.switchDarkMode.setOnCheckedChangeListener { _, checked ->
            binding.tvThemeLabel.text = if (checked) "Dark mode" else "Light mode"
            ThemePrefs.setNightMode(requireContext(), checked)
            requireActivity().recreate()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.exportLoading.collect { loading ->
                        binding.btnExportAll.isEnabled = !loading
                        binding.progressExport.visibility = if (loading) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    vm.message.collect { Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show() }
                }
            }
        }
    }

    // ── Widget settings ───────────────────────────────────────────────────────

    private fun setupWidgetCard() {
        val ctx   = requireContext()
        val prefs = ctx.getSharedPreferences("fed_widget_prefs", Context.MODE_PRIVATE)

        // Status: how many widget instances are active on the home screen
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, FedWidgetProvider::class.java))
        binding.tvWidgetStatus.text = when (ids.size) {
            0    -> "Widget name in picker: \"Fed Dashboard\"  ·  Not on home screen yet"
            1    -> "Widget name in picker: \"Fed Dashboard\"  ·  1 widget active"
            else -> "Widget name in picker: \"Fed Dashboard\"  ·  ${ids.size} widgets active"
        }

        // Toggle state
        val enabled = prefs.getBoolean(FedWidgetDataStore.PREF_WIDGET_ENABLED, true)
        binding.switchWidget.isChecked = enabled
        updateWidgetToggleHint(enabled)

        binding.switchWidget.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(FedWidgetDataStore.PREF_WIDGET_ENABLED, checked).apply()
            updateWidgetToggleHint(checked)
            if (checked) {
                // Re-enable: kick off a fresh fetch
                FedWidgetProvider.enqueueRefresh(ctx)
                Snackbar.make(binding.root, "Widget enabled — refreshing data.", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Widget disabled — background fetches paused.", Snackbar.LENGTH_SHORT).show()
            }
        }

        // Refresh Now button
        binding.btnRefreshWidget.setOnClickListener {
            if (ids.isEmpty()) {
                Snackbar.make(binding.root, "Add the widget to your home screen first (long-press home screen → Widgets → Fed Dashboard).", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            binding.tvWidgetRefreshHint.text = "Fetching…"
            FedWidgetProvider.enqueueRefresh(ctx)
            Snackbar.make(binding.root, "Widget refresh queued.", Snackbar.LENGTH_SHORT).show()
            binding.btnRefreshWidget.postDelayed({ binding.tvWidgetRefreshHint.text = "" }, 4000)
        }
    }

    private fun updateWidgetToggleHint(enabled: Boolean) {
        binding.tvWidgetToggleHint.text = if (enabled)
            "Fetches FRED data on each refresh tap"
        else
            "Widget paused — displays last cached values"
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
