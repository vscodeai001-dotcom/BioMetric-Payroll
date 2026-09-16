package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.biometric.app.api.TaxDeclarationRequest
import com.biometric.app.databinding.FragmentTaxDeclarationBinding
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TaxDeclarationFragment : Fragment() {

    private var _binding: FragmentTaxDeclarationBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var selfService: FirebaseEmployeeSelfServiceRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTaxDeclarationBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(
            view,
            savedInstanceState
        )

        loadTaxData()
        setupRealTimeSync()

        binding.btnSave.setOnClickListener {
            saveTaxData()
        }
    }

    private fun loadTaxData() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = selfService.tax(financialYear())
                _binding?.let { b ->
                    if (data != null) {
                        b.et80C.setText(data.section80C.toString())
                        b.et80D.setText(data.section80D.toString())
                        b.etHra.setText(data.hraRentPaid.toString())
                        b.etOther.setText(data.otherExemptions.toString())
                    }
                }
            } catch (e: Exception) {
                Log.e("Tax", "Firebase load failed", e)
                if (isAdded) Toast.makeText(requireContext(), "Failed to load tax declaration ❌ ⚠️", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveTaxData() {
        val request = TaxDeclarationRequest(
            financialYear = financialYear(),
            regime = "New",
            section80C = binding.et80C.text.toString().toDoubleOrNull() ?: 0.0,
            section80D = binding.et80D.text.toString().toDoubleOrNull() ?: 0.0,
            hraRentPaid = binding.etHra.text.toString().toDoubleOrNull() ?: 0.0,
            otherExemptions = binding.etOther.text.toString().toDoubleOrNull() ?: 0.0
        )
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                selfService.saveTax(request)
                Toast.makeText(requireContext(), "Tax declaration saved! ✅🏦 💎", Toast.LENGTH_SHORT).show()
                loadTaxData()
            } catch (e: Exception) {
                Log.e("Tax", "Firebase save failed", e)
                if (isAdded) Toast.makeText(requireContext(), "Failed to save declaration ❌ ⚠️", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun financialYear(): Int {
        val now = java.util.Calendar.getInstance()

        return if (
            now.get(java.util.Calendar.MONTH) >=
            java.util.Calendar.APRIL
        ) {
            now.get(java.util.Calendar.YEAR)
        } else {
            now.get(java.util.Calendar.YEAR) - 1
        }
    }

    private fun setupRealTimeSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            selfService.changesFlow().collect {
                if (isAdded && _binding != null) loadTaxData()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}