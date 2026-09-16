package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.biometric.app.databinding.FragmentApplyAdvanceBinding
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ApplyAdvanceFragment : Fragment() {

    private var _binding: FragmentApplyAdvanceBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var selfService: FirebaseEmployeeSelfServiceRepository

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
        val amountStr = _binding?.etAmount?.text?.toString().orEmpty()
        val reason = _binding?.etReason?.text?.toString().orEmpty()
        val amount = amountStr.toDoubleOrNull() ?: 0.0
        if (amountStr.isBlank()) {
            if (isAdded) Toast.makeText(requireContext(), "Please enter amount ⚠️", Toast.LENGTH_SHORT).show()
            return
        }
        if (amount <= 0) {
            if (isAdded) Toast.makeText(requireContext(), "Invalid amount ⚠️", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                selfService.createAdvance(amount, reason)
                Toast.makeText(requireContext(), "Advance request submitted! 💳 💎 ✅", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(requireContext(), "Submission error: ${e.message ?: "Firebase unavailable"} ⚠️", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
