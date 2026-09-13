package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.MobileApiService
import com.biometric.app.api.ResignationCreateRequest
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.databinding.FragmentResignationBinding
import com.biometric.app.sync.SignalRManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ResignationFragment : Fragment() {

    private var _binding: FragmentResignationBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var sessionStore: MobileSessionStore
    @Inject lateinit var signalR: SignalRManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentResignationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadResignationStatus()
        setupRealTimeSync()

        binding.btnSubmit.setOnClickListener {
            submitResignation()
        }
    }

    private fun loadResignationStatus() {
        val token = sessionStore.token() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = mobileApi.resignation("Bearer $token")
                _binding?.let { b ->
                    if (response.isSuccessful && response.body() != null) {
                        val data = response.body()!!
                        
                        b.cvStatus.visibility = View.VISIBLE
                        b.tvStatusText.text = data.status
                        b.tvAdminRemarks.text = data.adminRemarks ?: "No remarks from admin yet 🛡️."
                        
                        // Style based on status
                        val context = requireContext()
                        when (data.status.lowercase()) {
                            "approved" -> {
                                b.tvStatusText.text = "Approved ✅ 💎"
                                b.tvStatusText.setTextColor(ContextCompat.getColor(context, R.color.green_700))
                            }
                            "pending" -> {
                                b.tvStatusText.text = "Pending ⏳ 📊"
                                b.tvStatusText.setTextColor(ContextCompat.getColor(context, R.color.amber_900))
                            }
                            else -> {
                                b.tvStatusText.text = "${data.status} ❌ ⚠️"
                                b.tvStatusText.setTextColor(ContextCompat.getColor(context, R.color.red_700))
                            }
                        }

                        b.etLastDay.setText(data.desiredLastWorkingDay)
                        b.etReason.setText(data.reason)
                        
                        val active = data.status.equals("Pending", true) || data.status.equals("Approved", true)
                        val rejected = data.status.equals("Rejected", true)

                        b.btnSubmit.isEnabled = !active
                        b.btnSubmit.text = if (rejected) "Re-Submit Resignation 📤 🚪" else "Submit Resignation 📤 🚪"
                        b.etLastDay.isEnabled = !active
                        b.etReason.isEnabled = !active

                        if (data.approvedLastWorkingDay != null) {
                            b.tvAdminRemarks.text =
                                "${data.adminRemarks ?: "Approved ✅"} 🛡️\nApproved last working day: ${data.approvedLastWorkingDay} 📅 💎"
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun submitResignation() {
        val token = sessionStore.token() ?: return
        val lastDay = _binding?.etLastDay?.text?.toString() ?: ""
        val reason = _binding?.etReason?.text?.toString() ?: ""

        if (lastDay.isBlank() || reason.isBlank()) {
            if (isAdded) Toast.makeText(requireContext(), "Please fill all fields ⚠️", Toast.LENGTH_SHORT).show()
            return
        }

        val request = ResignationCreateRequest(lastDay, reason)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = mobileApi.createResignation("Bearer $token", request)
                _binding?.let { b ->
                    if (response.isSuccessful) {
                        Toast.makeText(requireContext(), "Resignation submitted successfully 🚪 💎", Toast.LENGTH_SHORT).show()
                        loadResignationStatus()
                    }
                }
            } catch (_: Exception) {
                if (isAdded) Toast.makeText(requireContext(), "Failed to submit resignation ❌ ⚠️", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun setupRealTimeSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            signalR.dataChangeEvents
                .debounce(500L)
                .collect { event ->
                    if (event is SignalRManager.SyncEvent.GlobalRefresh || event is SignalRManager.SyncEvent.ExitChanged) {
                        Log.d("ResignationFragment", "Real-time refresh: $event 🛰️")
                        if (isAdded && _binding != null) {
                            loadResignationStatus()
                        }
                    }
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
