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
import com.biometric.app.api.MobileApiService
import com.biometric.app.api.PayslipDto
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.databinding.FragmentPayslipListBinding
import com.biometric.app.sync.SignalRManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class PayslipListFragment : Fragment() {

    private var _binding: FragmentPayslipListBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var sessionStore: MobileSessionStore
    @Inject lateinit var signalR: SignalRManager

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

    @OptIn(FlowPreview::class)
    private fun setupRealTimeSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            signalR.dataChangeEvents
                .debounce(500L)
                .collect { event ->
                    Log.d("Payslips", "Real-time refresh: $event 🛰️")
                    if (isAdded && _binding != null) {
                        loadPayslips()
                    }
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
            append("Bonus: ₹${String.format(Locale.US, "%,.2f", payslip.bonus)} 🌟\n")
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
        val token = sessionStore.token() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = mobileApi.payslips("Bearer $token")
                _binding?.let { b ->
                    if (response.isSuccessful) {
                        val payslips = response.body() ?: emptyList()
                        adapter.submitList(payslips)
                        if (payslips.isEmpty()) {
                            b.tvEmpty.text = "No payslips available 🧾 ⚠️"
                            b.tvEmpty.visibility = View.VISIBLE
                        } else {
                            b.tvEmpty.visibility = View.GONE
                        }
                    } else {
                        Toast.makeText(requireContext(), "Server error: ${response.code()} ❌", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Payslips", "Load failed: ${e.message}")
                _binding?.let { b ->
                    b.tvEmpty.apply {
                        text = "Unable to load payslips. Please try again. ⚠️"
                        visibility = View.VISIBLE
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
