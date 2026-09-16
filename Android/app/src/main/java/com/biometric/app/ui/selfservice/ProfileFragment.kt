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
                val emp = selfService.employeeProfile()
                if (emp == null) throw IllegalStateException("Employee record not found in Firebase for employee ID ${selfService.currentEmployeeId()}")
                _binding?.let { b ->
                    b.tvProfileName.text = "${emp.name} 👤"
                    b.tvProfileRole.text = "${emp.role.ifBlank { "Staff Member" }} 👔"
                    setDetail(b.detailEmpId, "🆔 Employee ID", emp.employeeId)
                    setDetail(b.detailEmail, "✉️ Official Email", emp.email.orEmpty().ifBlank { "--" })
                    setDetail(b.detailHireDate, "🗓️ Joining Date", emp.hireDate.takeIf { it > 0 }?.let(selfService::formatEmployeeDate) ?: "--")
                    setDetail(b.detailShift, "🕒 Assigned Shift",
                        if (emp.shiftStart.isNotBlank()) "${emp.shiftStart} - ${emp.shiftEnd}" else "General Shift")
                    setDetail(b.detailSalary, "💰 Gross Salary",
                        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).format(emp.salaryRate))
                    setDetail(b.detailBank, "🏦 Bank Name", emp.bankName ?: "--")
                    setDetail(b.detailIfsc, "🔢 IFSC Code", emp.bankIfscCode ?: "--")
                    setDetail(b.detailUan, "🛡️ PF UAN", emp.uanNumber ?: "Not Enrolled")
                    setDetail(b.detailEsi, "🏥 ESI Number", emp.esiNumber ?: "Not Enrolled")
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
