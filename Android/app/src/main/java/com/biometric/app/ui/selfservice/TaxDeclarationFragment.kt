package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.biometric.app.api.MobileApiService
import com.biometric.app.api.TaxDeclarationRequest
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.databinding.FragmentTaxDeclarationBinding
import com.biometric.app.sync.SignalRManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TaxDeclarationFragment : Fragment() {

    private var _binding: FragmentTaxDeclarationBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var mobileApi: MobileApiService

    @Inject
    lateinit var sessionStore: MobileSessionStore

    @Inject
    lateinit var signalR: SignalRManager

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
        val token = sessionStore.token() ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            try {

                val response = mobileApi.tax(
                    "Bearer $token",
                    financialYear()
                )

                _binding?.let { b ->
                    if (response.isSuccessful) {

                        response.body()?.let { data ->

                            b.et80C.setText(
                                data.section80C.toString()
                            )

                            b.et80D.setText(
                                data.section80D.toString()
                            )

                            b.etHra.setText(
                                data.hraRentPaid.toString()
                            )

                            b.etOther.setText(
                                data.otherExemptions.toString()
                            )
                        }

                    } else {
                        if (isAdded) {
                            Toast.makeText(
                                requireContext(),
                                "Unable to load tax declaration ⚠️",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    "Tax",
                    "Load failed",
                    e
                )

                if (isAdded && _binding != null) {
                    Toast.makeText(
                        requireContext(),
                        "Failed to load tax declaration ❌ ⚠️",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun saveTaxData() {
        val token = sessionStore.token() ?: return

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
                val response = mobileApi.saveTax("Bearer $token", request)

                _binding?.let {
                    if (response.isSuccessful) {
                        Toast.makeText(requireContext(), "Tax declaration saved! ✅🏦 💎", Toast.LENGTH_SHORT).show()
                        loadTaxData()
                    } else {
                        Toast.makeText(requireContext(), "Failed to save declaration: ${response.code()} ❌ ⚠️", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Tax", "Save failed ⚠️", e)
                if (isAdded && _binding != null) {
                    Toast.makeText(requireContext(), "Failed to save declaration ❌ 🌐 ⚠️", Toast.LENGTH_SHORT).show()
                }
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

    @OptIn(FlowPreview::class)
    private fun setupRealTimeSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            signalR.dataChangeEvents
                .debounce(500L)
                .collect { event ->
                    Log.d("TaxDeclaration", "Real-time refresh: $event 🛰️")
                    if (isAdded && _binding != null) {
                        loadTaxData()
                    }
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}