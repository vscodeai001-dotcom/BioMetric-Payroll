package com.biometric.app.ui

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.biometric.app.data.entity.Employee
import com.biometric.app.databinding.DialogEmployeeDetailsBinding
import com.biometric.app.ui.viewmodel.StaffViewModel
import com.biometric.app.util.MotionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class EmployeeDetailsDialogFragment : DialogFragment() {

    private var _binding: DialogEmployeeDetailsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StaffViewModel by viewModels({ requireActivity() })
    private var employeeId: String? = null
    private var currentEmployee: Employee? = null

    companion object {
        const val TAG = "EmployeeDetailsDialog"
        private const val ARG_EMPLOYEE_ID = "employeeId"

        fun newInstance(employeeId: String): EmployeeDetailsDialogFragment {
            return EmployeeDetailsDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_EMPLOYEE_ID, employeeId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        employeeId = arguments?.getString(ARG_EMPLOYEE_ID)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogEmployeeDetailsBinding.inflate(layoutInflater)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Employee Profile 👤")
            .setView(binding.root)
            .setPositiveButton("Close", null)
            .create()

        dialog.setOnShowListener {
            MotionManager.applyDialogAnimation(dialog)
            val alertDialog = it as AlertDialog
            val closeBtn = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            MotionManager.applyTouchScale(closeBtn)
        }

        binding.btnDetailEditProfile.setOnClickListener {
            val empId = employeeId ?: currentEmployee?.employeeId
            dismiss()
            if (!empId.isNullOrBlank()) {
                AddStaffDialogFragment.newInstance(empId)
                    .show(parentFragmentManager, AddStaffDialogFragment.TAG)
            }
        }

        binding.btnDetailViewInsights.setOnClickListener {
            val empId = employeeId ?: currentEmployee?.employeeId
            dismiss()
            if (!empId.isNullOrBlank()) {
                val intent = Intent(requireContext(), StaffDetailActivity::class.java).apply {
                    putExtra("EMPLOYEE_ID", empId)
                    putExtra("EMPLOYEE_NAME", currentEmployee?.name ?: "")
                    putExtra("SHOP_ID", viewModel.shopId.value)
                }
                startActivity(intent)
            }
        }

        observeEmployee()

        return dialog
    }

    private fun observeEmployee() {
        val targetId = employeeId ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.employees.collectLatest { list ->
                    val emp = list.firstOrNull { it.employeeId == targetId }
                    if (emp != null) {
                        currentEmployee = emp
                        bindEmployee(emp)
                    }
                }
            }
        }
    }

    private fun bindEmployee(emp: Employee) {
        val inrFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
            maximumFractionDigits = 0
        }
        val sdfDate = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())

        // Header
        val initial = if (emp.name.isNotBlank()) emp.name.take(1).uppercase(Locale.getDefault()) else "?"
        binding.tvDetailAvatar.text = initial
        binding.tvDetailStaffName.text = if (emp.isActive) emp.name else "🚫 ${emp.name} (Terminated)"
        binding.tvDetailEmpIdBadge.text = if (emp.employeeId.isNotBlank()) "#${emp.employeeId}" else "#--"
        binding.tvDetailBiometricIdBadge.text = if (emp.biometricId.isNotBlank()) "🧬 ${emp.biometricId}" else "🧬 --"
        binding.tvDetailRoleBadge.text = "💼 ${if (emp.role.isNotBlank()) emp.role else "Staff"}"

        // Compensation
        val isHourly = emp.salaryType.equals("HOURLY", ignoreCase = true) || emp.salaryType.equals("PER_HOUR", ignoreCase = true)
        val isDaily = emp.salaryType.equals("DAILY_WAGE", ignoreCase = true) || emp.salaryType.equals("PER_DAY", ignoreCase = true)
        val paySuffix = when {
            isHourly -> "/hr"
            isDaily -> "/day"
            else -> "/mo"
        }
        binding.tvDetailGrossPay.text = "Gross: ${inrFormat.format(emp.salaryRate)}$paySuffix"
        binding.tvDetailCalcMethod.text = "Calc: ${emp.salaryCalculationMethod}"

        val hasComponents = emp.basicSalaryComponent > 0 || emp.hraComponent > 0 || emp.daComponent > 0
        if (hasComponents) {
            binding.llDetailComponents.visibility = View.VISIBLE
            binding.tvDetailBasic.text = "Basic: ${inrFormat.format(emp.basicSalaryComponent)}"
            binding.tvDetailHra.text = "HRA: ${inrFormat.format(emp.hraComponent)}"
            binding.tvDetailDa.text = "DA: ${inrFormat.format(emp.daComponent)}"
        } else {
            binding.llDetailComponents.visibility = View.GONE
        }

        binding.tvDetailAllowances.text = "Allowances: ${inrFormat.format(emp.dailyAllowance)} daily • ${inrFormat.format(emp.nightShiftAllowance)} night"
        binding.tvDetailTds.text = "TDS: ${String.format(Locale.getDefault(), "%.1f", emp.tdsRatePercent)}%"

        // Shift & Rules
        val shiftStartFmt = formatTimeAmPm(emp.shiftStart)
        val shiftEndFmt = formatTimeAmPm(emp.shiftEnd)
        binding.tvDetailShiftTime.text = "Shift: $shiftStartFmt – $shiftEndFmt"
        val isContinuous = emp.shiftMode.equals("CONTINUOUS", ignoreCase = true)
        binding.tvDetailShiftMode.text = if (isContinuous) "Continuous Shift" else "Single Day Shift"

        val breakMinutes = (emp.breakHours * 60).toInt()
        val compOffStr = formatDayOfWeek(emp.compOffDayOfWeek)
        binding.tvDetailBreakCompOff.text = "☕ ${breakMinutes}m break • 🏖️ $compOffStr Off"
        binding.tvDetailOtRule.text = "⚡ OT: ${if (emp.otRule.isNotBlank()) emp.otRule else "1.0x"}"

        val isShiftTracking = emp.trackingMode.trim().uppercase().contains("SHIFT")
        binding.tvDetailTrackingMode.text = if (isShiftTracking) "🛰️ Mode: Shift Time Only" else "🛰️ Mode: 24/7 Tracking"

        // Statutory & Bank
        val pfText = if (emp.enablePf) "PF: Enabled (UAN: ${emp.uanNumber ?: "--"})" else "PF: Disabled"
        val esiText = if (emp.enableEsi) "ESI: Enabled (ESI: ${emp.esiNumber ?: "--"})" else "ESI: Disabled"
        binding.tvDetailPfEsi.text = "$pfText • $esiText"

        val bankName = emp.bankName ?: "--"
        val bankAc = emp.bankAccountNumber ?: "--"
        val bankIfsc = emp.bankIfscCode ?: "--"
        binding.tvDetailBank.text = "Bank: $bankName • A/C: $bankAc • IFSC: $bankIfsc"

        val phone = if (emp.phone.isNotBlank()) emp.phone else "--"
        val email = if (!emp.email.isNullOrBlank()) emp.email else "--"
        binding.tvDetailContact.text = "📞 Phone: $phone • ✉️ Email: $email"

        // Dates & Rules
        val hireStr = if (emp.hireDate > 0) sdfDate.format(Date(emp.hireDate)) else "--"
        val dobStr = if (emp.dob != null && emp.dob!! > 0) sdfDate.format(Date(emp.dob!!)) else "--"
        val termStr = if (emp.terminateDate != null && emp.terminateDate!! > 0) {
            " • 🔴 Terminated: ${sdfDate.format(Date(emp.terminateDate!!))}"
        } else ""
        binding.tvDetailDates.text = "Joined: $hireStr • DOB: $dobStr$termStr"

        val bonusStr = if (emp.isBonusEligibleRule) "Bonus: Eligible" else "Bonus: Ineligible"
        val plStr = when {
            !emp.isPaidLeaveEligibleRule -> "Paid Leave: Ineligible"
            emp.paidLeaveOnWeekdays && emp.paidLeaveOnWeekends -> "Paid Leave: All Days"
            emp.paidLeaveOnWeekdays -> "Paid Leave: Weekdays only"
            emp.paidLeaveOnWeekends -> "Paid Leave: Weekends only"
            else -> "Paid Leave: Eligible"
        }
        binding.tvDetailRules.text = "$bonusStr • $plStr"
        binding.tvDetailLeaveBalances.text = "Paid Leave Bal: ${String.format(Locale.getDefault(), "%.1f", emp.paidLeaveBalance)}d • Sick Leave Bal: ${String.format(Locale.getDefault(), "%.1f", emp.sickLeaveBalance)}d"
    }

    private fun formatTimeAmPm(timeStr: String?): String {
        if (timeStr.isNullOrBlank()) return "--"
        return try {
            val parts = timeStr.trim().split(":")
            val hour = parts[0].toInt()
            val minute = if (parts.size > 1) parts[1].toInt() else 0
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
            }
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time).lowercase(Locale.getDefault())
        } catch (e: Exception) {
            timeStr
        }
    }

    private fun formatDayOfWeek(dayIndex: Int?): String = when (dayIndex) {
        0 -> "Sunday"
        1 -> "Monday"
        2 -> "Tuesday"
        3 -> "Wednesday"
        4 -> "Thursday"
        5 -> "Friday"
        6 -> "Saturday"
        else -> "None"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
