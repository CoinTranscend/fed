package com.sun.feddashboard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.snackbar.Snackbar
import com.sun.feddashboard.MainViewModel
import com.sun.feddashboard.databinding.FragmentSettingsBinding

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
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
