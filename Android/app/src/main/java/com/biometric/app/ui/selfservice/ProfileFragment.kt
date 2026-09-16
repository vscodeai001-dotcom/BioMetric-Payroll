package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import com.biometric.app.databinding.FragmentProfileBinding
import com.biometric.app.databinding.ItemProfileDetailBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var selfService: FirebaseEmployeeSelfServiceRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadProfile()
        setupRealTimeSync()
    }

    private fun loadProfile() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = selfService.dashboard()
                _binding?.let { b ->
                    b.tvProfileName.text = "${data.name} 👤"
                    b.tvProfileRole.text = "${data.role ?: "Staff Member"} 👔"
                    setDetail(b.detailEmpId, "🆔 Employee ID", data.employeeId.toString())
                    setDetail(b.detailEmail, "✉️ Official Email", data.email)
                    setDetail(b.detailHireDate, "🗓️ Joining Date", data.hireDate ?: "--")
                    setDetail(b.detailShift, "🕒 Assigned Shift",
                        if (!data.shiftStartTime.isNullOrBlank()) "${data.shiftStartTime} - ${data.shiftEndTime ?: "--"}" else "General Shift")
                    setDetail(b.detailSalary, "💰 Gross Salary",
                        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).format(data.monthlySalary))
                    setDetail(b.detailBank, "🏦 Bank Name", data.bankName ?: "--")
                    setDetail(b.detailIfsc, "🔢 IFSC Code", data.bankIfscCode ?: "--")
                    setDetail(b.detailUan, "🛡️ PF UAN", data.uan ?: "Not Enrolled")
                    setDetail(b.detailEsi, "🏥 ESI Number", data.esiNumber ?: "Not Enrolled")
                }
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(requireContext(), "Error loading profile: ${e.message ?: "Firebase unavailable"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setDetail(includeBinding: ItemProfileDetailBinding, label: String, value: String) {
        includeBinding.tvLabel.text = label
        includeBinding.tvValue.text = value
    }

    @OptIn(FlowPreview::class)
    private fun setupRealTimeSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            selfService.changesFlow().debounce(500L).collect {
                if (isAdded && _binding != null) loadProfile()
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
