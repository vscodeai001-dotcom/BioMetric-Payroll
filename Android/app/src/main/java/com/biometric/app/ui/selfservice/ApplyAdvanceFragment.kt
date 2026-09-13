package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.biometric.app.api.AdvanceCreateRequest
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.databinding.FragmentApplyAdvanceBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ApplyAdvanceFragment : Fragment() {

    private var _binding: FragmentApplyAdvanceBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var sessionStore: MobileSessionStore

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentApplyAdvanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnSubmit.setOnClickListener {
            submitRequest()
        }
    }

    private fun submitRequest() {
        val amountStr = _binding?.etAmount?.text?.toString() ?: ""
        val reason = _binding?.etReason?.text?.toString() ?: ""

        if (amountStr.isBlank()) {
            if (isAdded) Toast.makeText(requireContext(), "Please enter amount ⚠️", Toast.LENGTH_SHORT).show()
            return
        }

        val amount = amountStr.toDoubleOrNull() ?: 0.0
        if (amount <= 0) {
            if (isAdded) Toast.makeText(requireContext(), "Invalid amount ⚠️", Toast.LENGTH_SHORT).show()
            return
        }

        val token = sessionStore.token() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val request = AdvanceCreateRequest(amount, reason)
                val response = mobileApi.createAdvance("Bearer $token", request)
                _binding?.let {
                    if (response.isSuccessful) {
                        Toast.makeText(requireContext(), "Advance request submitted! 💳 💎 ✅", Toast.LENGTH_SHORT).show()
                        parentFragmentManager.popBackStack()
                    } else {
                        Toast.makeText(requireContext(), "Submission failed: ${response.code()} ❌ ⚠️", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (isAdded && _binding != null) {
                    Toast.makeText(requireContext(), "Submission error 🌐 ⚠️", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
