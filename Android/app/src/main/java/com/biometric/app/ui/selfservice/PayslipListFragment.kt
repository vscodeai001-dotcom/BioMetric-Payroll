package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.biometric.app.api.PayslipDto
import com.biometric.app.databinding.FragmentPayslipListBinding
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class PayslipListFragment : Fragment() {

    private var _binding: FragmentPayslipListBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var selfService: FirebaseEmployeeSelfServiceRepository

    private lateinit var adapter: PayslipAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPayslipListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        loadPayslips()
        setupRealTimeSync()
    }

    private fun setupRealTimeSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            selfService.changesFlow().collect {
                if (isAdded && _binding != null) loadPayslips()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = PayslipAdapter { payslip ->
            viewPayslip(payslip)
        }
        _binding?.let { b ->
            b.rvPayslips.layoutManager = LinearLayoutManager(requireContext())
            b.rvPayslips.adapter = adapter
        }
    }

    private fun viewPayslip(payslip: PayslipDto) {
        val monthName = java.text.DateFormatSymbols().months.getOrNull(payslip.month - 1) ?: "Payroll"
        val message = buildString {
            append("$monthName ${payslip.year} 🗓️\n\n")
            append("Base Salary: ₹${String.format(Locale.US, "%,.2f", payslip.baseSalary)} 💰\n")
            append("Hourly Rate: ₹${String.format(Locale.US, "%,.2f", payslip.hourlyRate)} 🕒\n")
            append("Total Hours: ${String.format(Locale.US, "%.2f", payslip.totalHoursWorked)} hrs ⚡\n")
            append("Overtime Pay: ₹${String.format(Locale.US, "%,.2f", payslip.overtimePay)} 🔥\n")
            if (payslip.totalShiftAllowance > 0) {
                append("Night/Shift Allowance: ₹${String.format(Locale.US, "%,.2f", payslip.totalShiftAllowance)} 🌙\n")
            }
            append("Bonus: ₹${String.format(Locale.US, "%,.2f", payslip.bonus)} 🌟\n")
            if (payslip.penaltyDeduction > 0) {
                append("Penalty/Lateness: -₹${String.format(Locale.US, "%,.2f", payslip.penaltyDeduction)} ⏱️\n")
            }
            append("Advance Deduction: ₹${String.format(Locale.US, "%,.2f", payslip.advanceDeduction)} 💳\n")
            append("PF: ₹${String.format(Locale.US, "%,.2f", payslip.pfDeduction)} 🛡️\n")
            append("ESI: ₹${String.format(Locale.US, "%,.2f", payslip.esiDeduction)} 🏥\n")
            append("PT: ₹${String.format(Locale.US, "%,.2f", payslip.ptDeduction)} 🏛️\n")
            append("TDS: ₹${String.format(Locale.US, "%,.2f", payslip.tdsDeduction)} 🧾\n")
            append("Total Deductions: ₹${String.format(Locale.US, "%,.2f", payslip.totalDeductions)} 📉\n\n")
            append("Net Payable: ₹${String.format(Locale.US, "%,.2f", payslip.netSalary)} 💎 ✨")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Premium Payslip Details 💎")
            .setMessage(message)
            .setPositiveButton("Close 🛡️", null)
            .show()
    }

    private fun loadPayslips() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val payslips = selfService.payslips()
                _binding?.let { b ->
                    adapter.submitList(payslips)
                    b.tvEmpty.visibility = if (payslips.isEmpty()) View.VISIBLE else View.GONE
                    if (payslips.isEmpty()) b.tvEmpty.text = "No payslips available 🧾 ⚠️"
                }
            } catch (e: Exception) {
                Log.e("Payslips", "Firebase load failed: ${e.message}", e)
                _binding?.let { b ->
                    b.tvEmpty.text = "Unable to load payslips. Please try again. ⚠️"
                    b.tvEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
