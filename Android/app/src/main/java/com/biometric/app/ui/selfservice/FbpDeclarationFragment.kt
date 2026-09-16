package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.FbpRequest
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import com.biometric.app.databinding.FragmentFbpDeclarationBinding
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class FbpDeclarationFragment : Fragment() {

    @Inject lateinit var selfService: FirebaseEmployeeSelfServiceRepository

    private var _binding: FragmentFbpDeclarationBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFbpDeclarationBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadFbpData()
        setupRealTimeSync()

        binding.btnSave.setOnClickListener {
            saveFbpData()
        }
    }

    private fun loadFbpData() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = selfService.fbp(financialYear())

                _binding?.let { b ->

                        b.fbpHistoryContainer.removeAllViews()

                        if (data.isNotEmpty()) {
                            val first = data.first()

                            b.etComponent.setText(
                                first.componentName
                            )

                            b.etAmount.setText(
                                first.annualAllocatedAmount.toString()
                            )

                            data.forEach { item ->

                                val card = MaterialCardView(
                                    requireContext()
                                ).apply {
                                    radius = 16f
                                    cardElevation = 2f
                                    setContentPadding(
                                        16,
                                        12,
                                        16,
                                        12
                                    )
                                    useCompatPadding = true
                                }

                                val annual = String.format(
                                    Locale.US,
                                    "%,.2f",
                                    item.annualAllocatedAmount
                                )

                                val monthly = String.format(
                                    Locale.US,
                                    "%,.2f",
                                    item.monthlyAllocatedAmount
                                )

                                val remarks = item.adminRemarks
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { "  •  $it 🛡️" }
                                    ?: ""

                                val statusEmoji = when(item.status.lowercase()) {
                                    "approved" -> "✅ 💎"
                                    "pending" -> "⏳ 📊"
                                    else -> "❌ ⚠️"
                                }

                                val tv = TextView(
                                    requireContext()
                                ).apply {
                                    text =
                                        "${item.componentName} 🎁\n" +
                                                "Annual: ₹$annual  •  " +
                                                "Monthly: ₹$monthly\n" +
                                                "Status: ${item.status} $statusEmoji$remarks"

                                    textSize = 12f

                                    setTextColor(
                                        ContextCompat.getColor(
                                            requireContext(),
                                            R.color.text_primary
                                        )
                                    )
                                }

                                card.addView(tv)
                                b.fbpHistoryContainer.addView(card)
                            }

                        } else {

                            val empty = TextView(
                                requireContext()
                            ).apply {
                                text =
                                    "No FBP declarations found for the current financial year 🎁 💎."

                                textSize = 12f

                                setTextColor(
                                    ContextCompat.getColor(
                                        requireContext(),
                                        R.color.text_secondary
                                    )
                                )

                                setPadding(
                                    8,
                                    8,
                                    8,
                                    8
                                )
                            }

                            b.fbpHistoryContainer.addView(empty)
                        }
                }
            } catch (e: Exception) {

                Log.e(
                    "FBP",
                    "Load failed",
                    e
                )

                if (isAdded && _binding != null) {
                    Toast.makeText(
                        requireContext(),
                        "Failed to load FBP declarations ❌ ⚠️",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun saveFbpData() {
        val componentName = binding.etComponent.text?.toString()?.trim().orEmpty()
        val annualAmount = binding.etAmount.text?.toString()?.trim()?.toDoubleOrNull()

        if (componentName.isBlank()) {
            binding.etComponent.error = "Component is required ⚠️"
            return
        }

        if (annualAmount == null || annualAmount < 0) {
            binding.etAmount.error = "Enter a valid amount ⚠️"
            return
        }

        val request = FbpRequest(
            financialYear = financialYear(),
            componentName = componentName,
            annualAllocatedAmount = annualAmount
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                selfService.saveFbp(request)
                Toast.makeText(requireContext(), "FBP declaration saved! ✅🎁 💎", Toast.LENGTH_SHORT).show()
                loadFbpData()
            } catch (e: Exception) {
                Log.e("FBP", "Save failed ⚠️", e)
                if (isAdded && _binding != null) {
                    Toast.makeText(requireContext(), "Failed to save FBP ❌ 🌐 ⚠️", Toast.LENGTH_SHORT).show()
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
            selfService.changesFlow()
                .debounce(500L)
                .collect {
                    Log.d("FBPDeclaration", "Real-time refresh: Firebase update 🛰️")
                    if (isAdded && _binding != null) {
                        loadFbpData()
                    }
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}