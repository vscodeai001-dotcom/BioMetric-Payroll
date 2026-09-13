package com.biometric.app.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.biometric.app.databinding.DialogPaySalaryBinding
import com.biometric.app.ui.viewmodel.StaffViewModel
import com.biometric.app.util.HapticUtil
import dagger.hilt.android.AndroidEntryPoint
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

@AndroidEntryPoint
class PaySalaryDialogFragment : DialogFragment() {

    private val viewModel: StaffViewModel by activityViewModels()
    private var _binding: DialogPaySalaryBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TAG = "PaySalaryDialog"
        private const val ARG_EMPLOYEE_ID = "employeeId"
        private const val ARG_EMPLOYEE_NAME = "employeeName"

        fun newInstance(employeeId: String, employeeName: String): PaySalaryDialogFragment {
            val args = Bundle().apply {
                putString(ARG_EMPLOYEE_ID, employeeId)
                putString(ARG_EMPLOYEE_NAME, employeeName)
            }
            return PaySalaryDialogFragment().apply {
                arguments = args
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val employeeId = requireArguments().getString(ARG_EMPLOYEE_ID)!!
        val employeeName = requireArguments().getString(ARG_EMPLOYEE_NAME)!!
        
        _binding = DialogPaySalaryBinding.inflate(layoutInflater)
        val stats = viewModel.employeeStats.value[employeeId]
        
        // Components for Net Payable calculation
        val workSal = stats?.totalNormalWorkedSalaryMonth ?: BigDecimal.ZERO
        val otAmt = stats?.totalOTSalaryMonth ?: BigDecimal.ZERO
        val paidLeavePay = stats?.paidLeaveSalary ?: BigDecimal.ZERO
        val bonus = stats?.bonusAmount ?: BigDecimal.ZERO
        
        // Info Only fields
        val allowance = stats?.monthlyAllowance ?: BigDecimal.ZERO
        val advance = stats?.pendingAdvance ?: 0.0
        
        binding.tvEmployeeName.text = employeeName
        binding.tvEarnings.text = String.format(Locale.getDefault(), "Work Sal: ₹%.2f | OT: ₹%.2f | PL: ₹%.2f", workSal.toDouble(), otAmt.toDouble(), paidLeavePay.toDouble())
        binding.tvAllowance.text = String.format(Locale.getDefault(), "Daily Allowance (Info Only): ₹%.2f", allowance.toDouble())
        binding.tvAdvance.text = String.format(Locale.getDefault(), "Advance (Info Only): ₹%.2f", advance)
        binding.tvBonusAmount.text = String.format(Locale.getDefault(), "Attendance Bonus: ₹%.2f", bonus.toDouble())
        
        // Initial state from engine (already includes overrides)
        binding.cbIncludeBonus.isChecked = stats?.isBonusEligible == true
        binding.cbIncludeBonus.isEnabled = false // Read-only in final pay dialog to prevent desync with staff screen
        
        updateTotal(workSal, otAmt, paidLeavePay, bonus)

        return AlertDialog.Builder(requireActivity())
            .setTitle("Settle Monthly Salary")
            .setView(binding.root)
            .setPositiveButton("Process Pay") { _, _ ->
                viewModel.employees.value.find { it.employeeId == employeeId }?.let {
                    val cal = viewModel.selectedCalendar.value.clone() as Calendar
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                    
                    val endCal = viewModel.selectedCalendar.value.clone() as Calendar
                    endCal.set(Calendar.DAY_OF_MONTH, endCal.getActualMaximum(Calendar.DAY_OF_MONTH))
                    endCal.set(Calendar.HOUR_OF_DAY, 23); endCal.set(Calendar.MINUTE, 59)

                    viewModel.calculateAndPaySalary(
                        it,
                        cal.timeInMillis,
                        endCal.timeInMillis
                    )
                    HapticUtil.vibrateSuccess(binding.root)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    private fun updateTotal(workSal: BigDecimal, otAmt: BigDecimal, plPay: BigDecimal, bonus: BigDecimal) {
        // Net Payable = Work Sal + OT Amt + Paid Leave Pay + Bonus
        val totalNet = workSal.add(otAmt).add(plPay).add(bonus).setScale(0, RoundingMode.HALF_UP)
        binding.tvTotalPayable.text = String.format("Total Net Payable: ₹%.2f", totalNet.toDouble())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
