package com.biometric.app.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.biometric.app.R
import com.biometric.app.data.entity.SalaryRules
import com.biometric.app.databinding.DialogAddStaffBinding
import com.biometric.app.ui.viewmodel.StaffViewModel
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.MotionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

@AndroidEntryPoint
class AddStaffDialogFragment : DialogFragment() {

    private var _binding: DialogAddStaffBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StaffViewModel by viewModels({ requireActivity() })
    private var employeeId: String? = null

    private var hireDate: Long = System.currentTimeMillis()
    private var dob: Long? = null
    private var terminateDate: Long? = null
    private var allowanceEffectiveDate: Long = System.currentTimeMillis()
    private var effectiveDate: Long = System.currentTimeMillis()
    private var salaryRulesOverride: SalaryRules? = null

    private var isDataInitialized = false
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    companion object {
        const val TAG = "AddStaffDialog"
        private const val ARG_EMPLOYEE_ID = "employeeId"

        fun newInstance(employeeId: String? = null): AddStaffDialogFragment {
            return AddStaffDialogFragment().apply {
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
        _binding = DialogAddStaffBinding.inflate(layoutInflater)

        setupDropdowns()
        setupDatePickers()
        observeData()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (employeeId == null) "New Employee Record" else "Edit Employee Record")
            .setView(binding.root)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            MotionManager.applyDialogAnimation(dialog)
            
            val alertDialog = it as AlertDialog
            val saveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            
            MotionManager.applyTouchScale(saveButton)
            MotionManager.applyTouchScale(cancelButton)
            
            saveButton.setOnClickListener {
                if (validateAndSave()) {
                    dialog.dismiss()
                }
            }
        }

        MotionManager.applyTouchScale(binding.btnIndividualRules)
        binding.cbEnableRotation.setOnCheckedChangeListener { _, isChecked ->
            binding.etRotationGroup.isEnabled = isChecked
            binding.etRotationPattern.isEnabled = isChecked
        }
        binding.toggleSalaryType.addOnButtonCheckedListener { _, _, _ ->
            HapticUtil.vibrateClick(binding.root)
        }

        setupAutoFocus()

        return dialog
    }

    private fun setupDropdowns() {
        val calcMethods = arrayOf("Pro-Rata Hourly", "Fixed 30-Day", "Fixed 26-Day", "Days in Month")
        val calcAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, calcMethods)
        binding.etCalcMethod.setAdapter(calcAdapter)

        val compOffDays = arrayOf("None", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val compOffAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, compOffDays)
        binding.etCompOff.setAdapter(compOffAdapter)

        val otRules = arrayOf("No Overtime", "1.0x", "1.5x", "2.0x", "Flat")
        val otAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, otRules)
        binding.etOtRule.setAdapter(otAdapter)
    }

    private var autoFocusJob: Job? = null

    private fun setupAutoFocus() {
        binding.etStaffName.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (binding.etStaffName.isFocused && !s.isNullOrBlank()) {
                        autoFocusJob?.cancel()
                        autoFocusJob = lifecycleScope.launch {
                            delay(1200.milliseconds)
                            binding.etBiometricId.requestFocus()
                        }
                    }
                }
            },
        )
    }

    private fun observeData() {
        if (employeeId != null) {
            binding.tilEffectiveDate.visibility = View.VISIBLE
            viewModel.loadEmployee(employeeId!!)
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.selectedEmployee.collectLatest { employee ->
                        employee?.let {
                            if (!isDataInitialized) {
                                binding.etStaffName.setText(it.name)
                                binding.etBiometricId.setText(it.biometricId)
                                binding.etRole.setText(it.role)
                                binding.etStaffEmail.setText(it.email)
                                binding.etStaffPhone.setText(it.phone)
                                
                                binding.etSalaryRate.setText(String.format(Locale.getDefault(), "%.2f", it.salaryRate))
                                binding.etCalcMethod.setText(it.salaryCalculationMethod, false)
                                binding.etDailyAllowance.setText(String.format(Locale.getDefault(), "%.2f", it.dailyAllowance))
                                binding.etNightAllowance.setText(String.format(Locale.getDefault(), "%.2f", it.nightShiftAllowance))
                                
                                binding.etShiftStart.setText(it.shiftStart)
                                binding.etShiftEnd.setText(it.shiftEnd)
                                binding.etBreakMinutes.setText(((it.breakHours * 60).toInt()).toString())

                                val compOffText = when(it.compOffDayOfWeek) {
                                    Calendar.SUNDAY -> "Sunday"
                                    Calendar.MONDAY -> "Monday"
                                    Calendar.TUESDAY -> "Tuesday"
                                    Calendar.WEDNESDAY -> "Wednesday"
                                    Calendar.THURSDAY -> "Thursday"
                                    Calendar.FRIDAY -> "Friday"
                                    Calendar.SATURDAY -> "Saturday"
                                    else -> "None"
                                }
                                binding.etCompOff.setText(compOffText, false)
                                binding.etOtRule.setText(it.otRule, false)
                                binding.etOtFlatRate.setText(String.format(Locale.getDefault(), "%.2f", it.otFlatRate))
                                
                                lifecycleScope.launch {
                                    viewModel.selectedStaffProfile.collectLatest { profile ->
                                        profile?.let { p ->
                                            binding.etLoginId.setText(p.employeeId)
                                            binding.etPassword.setText(p.password)
                                        }
                                    }
                                }

                                binding.etBankAccountNumber.setText(it.bankAccountNumber)
                                binding.etBankIfscCode.setText(it.bankIfscCode)
                                binding.etBankName.setText(it.bankName)
                                binding.etUanNumber.setText(it.uanNumber)
                                binding.etEsiNumber.setText(it.esiNumber)
                                binding.cbEnablePf.isChecked = it.enablePf
                                binding.cbEnableEsi.isChecked = it.enableEsi
                                binding.etTdsRate.setText(String.format(Locale.getDefault(), "%.2f", it.tdsRatePercent))

                                binding.cbEnableRotation.isChecked = it.enableShiftRotation
                                binding.etRotationGroup.setText(it.rotationGroup)
                                binding.etRotationPattern.setText(it.shiftRotationPattern)

                                hireDate = it.hireDate
                                dob = it.dob
                                terminateDate = it.terminateDate
                                allowanceEffectiveDate = it.allowanceEffectiveDate
                                
                                val cal = viewModel.selectedCalendar.value.clone() as Calendar
                                cal[Calendar.DAY_OF_MONTH] = 1
                                effectiveDate = cal.timeInMillis
                                
                                updateDateFields()

                                if (it.salaryType == "MONTHLY_FIXED") {
                                    binding.toggleSalaryType.check(R.id.btnMonthly)
                                } else {
                                    binding.toggleSalaryType.check(R.id.btnHourly)
                                }

                                binding.cbBonusEligible.isChecked = it.isBonusEligibleRule
                                binding.cbPaidLeaveEligible.isChecked = it.isPaidLeaveEligibleRule
                                salaryRulesOverride = it.salaryRulesOverride
                                isDataInitialized = true
                            }
                        }
                    }
                }
            }
        } else {
            binding.tilEffectiveDate.visibility = View.GONE
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.salaryRules.collectLatest { rules ->
                        if (employeeId == null) {
                            binding.etShiftStart.setText(rules.defaultShiftStart)
                            binding.etShiftEnd.setText(rules.defaultShiftEnd)
                            binding.cbBonusEligible.isChecked = rules.isBonusEligibleDefault
                            binding.cbPaidLeaveEligible.isChecked = rules.isPaidLeaveEligibleDefault
                        }
                    }
                }
            }
            updateDateFields()
        }
    }

    private fun validateAndSave(): Boolean {
        val name = binding.etStaffName.text.toString().trim()
        val bioId = binding.etBiometricId.text.toString().trim()
        val loginId = binding.etLoginId.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val phone = binding.etStaffPhone.text.toString().trim()
        val rateText = binding.etSalaryRate.text.toString().trim()
        val calcMethod = binding.etCalcMethod.text.toString()
        val start = binding.etShiftStart.text.toString().trim()
        val end = binding.etShiftEnd.text.toString().trim()
        val breakMins = binding.etBreakMinutes.text.toString().toIntOrNull() ?: 60
        val otRule = binding.etOtRule.text.toString()
        val otFlatRate = binding.etOtFlatRate.text.toString().toDoubleOrNull() ?: 0.0

        var isValid = true
        if (name.isEmpty()) { binding.tilStaffName.error = "Required"; isValid = false }
        if (loginId.isEmpty()) { binding.tilLoginId.error = "Required"; isValid = false }
        if (password.isEmpty()) { binding.tilPassword.error = "Required"; isValid = false }
        
        val rate = rateText.toDoubleOrNull() ?: 0.0
        if (rate <= 0) { binding.tilSalaryRate.error = "Invalid rate"; isValid = false }

        if (isValid) {
            val type = if (binding.toggleSalaryType.checkedButtonId == R.id.btnHourly) "PER_HOUR" else "MONTHLY_FIXED"
            val compOffVal = when(binding.etCompOff.text.toString()) {
                "Sunday" -> Calendar.SUNDAY
                "Monday" -> Calendar.MONDAY
                "Tuesday" -> Calendar.TUESDAY
                "Wednesday" -> Calendar.WEDNESDAY
                "Thursday" -> Calendar.THURSDAY
                "Friday" -> Calendar.FRIDAY
                "Saturday" -> Calendar.SATURDAY
                else -> null
            }

            try {
                if (employeeId == null) {
                    val success = viewModel.addEmployeeDetailed(
                        name = name, bioId = bioId, role = binding.etRole.text.toString(), email = binding.etStaffEmail.text.toString(),
                        phone = phone, salaryRate = rate, type = type, calcMethod = calcMethod,
                        start = start, end = end, breakHours = breakMins / 60.0,
                        otRule = otRule, otFlatRate = otFlatRate, compOff = compOffVal,
                        hireDate = hireDate, dob = dob,
                        loginId = loginId, password = password,
                        bankAccount = binding.etBankAccountNumber.text.toString(), bankIfsc = binding.etBankIfscCode.text.toString(),
                        bankName = binding.etBankName.text.toString(), uan = binding.etUanNumber.text.toString(),
                        esiNum = binding.etEsiNumber.text.toString(), enablePf = binding.cbEnablePf.isChecked,
                        enableEsi = binding.cbEnableEsi.isChecked, tdsRate = binding.etTdsRate.text.toString().toDoubleOrNull() ?: 0.0,
                        allowance = binding.etDailyAllowance.text.toString().toDoubleOrNull() ?: 0.0,
                        nightAllowance = binding.etNightAllowance.text.toString().toDoubleOrNull() ?: 0.0,
                        enableRotation = binding.cbEnableRotation.isChecked,
                        rotGroup = binding.etRotationGroup.text.toString(), rotPattern = binding.etRotationPattern.text.toString(),
                        bonusEligible = binding.cbBonusEligible.isChecked, plEligible = binding.cbPaidLeaveEligible.isChecked
                    )
                    if (!success) {
                        Toast.makeText(context, "Error: No workplace selected. Please select a shop first.", Toast.LENGTH_LONG).show()
                        return false
                    }
                } else {
                    viewModel.updateEmployeeDetailed(
                        empId = employeeId!!, name = name, bioId = bioId, role = binding.etRole.text.toString(), email = binding.etStaffEmail.text.toString(),
                        phone = phone, salaryRate = rate, type = type, calcMethod = calcMethod,
                        start = start, end = end, breakHours = breakMins / 60.0,
                        otRule = otRule, otFlatRate = otFlatRate, compOff = compOffVal,
                        hireDate = hireDate, dob = dob, terminateDate = terminateDate,
                        loginId = loginId, password = password,
                        bankAccount = binding.etBankAccountNumber.text.toString(), bankIfsc = binding.etBankIfscCode.text.toString(),
                        bankName = binding.etBankName.text.toString(), uan = binding.etUanNumber.text.toString(),
                        esiNum = binding.etEsiNumber.text.toString(), enablePf = binding.cbEnablePf.isChecked,
                        enableEsi = binding.cbEnableEsi.isChecked, tdsRate = binding.etTdsRate.text.toString().toDoubleOrNull() ?: 0.0,
                        allowance = binding.etDailyAllowance.text.toString().toDoubleOrNull() ?: 0.0,
                        nightAllowance = binding.etNightAllowance.text.toString().toDoubleOrNull() ?: 0.0,
                        enableRotation = binding.cbEnableRotation.isChecked,
                        rotGroup = binding.etRotationGroup.text.toString(), rotPattern = binding.etRotationPattern.text.toString(),
                        bonusEligible = binding.cbBonusEligible.isChecked, plEligible = binding.cbPaidLeaveEligible.isChecked,
                        effectiveDate = effectiveDate
                    )
                }
                HapticUtil.vibrateSuccess(binding.root)
                Toast.makeText(context, "Employee record saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                return false
            }
        }
        return isValid
    }

    private fun setupDatePickers() {
        binding.etDob.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = dob ?: System.currentTimeMillis() }
            DatePickerDialog(requireContext(), { _, y, m, d ->
                cal[Calendar.YEAR] = y
                cal[Calendar.MONTH] = m
                cal[Calendar.DAY_OF_MONTH] = d
                dob = cal.timeInMillis
                updateDateFields()
            }, cal[Calendar.YEAR], cal[Calendar.MONTH], cal[Calendar.DAY_OF_MONTH]).show()
        }
        binding.etHireDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = hireDate }
            DatePickerDialog(requireContext(), { _, y, m, d ->
                cal[Calendar.YEAR] = y
                cal[Calendar.MONTH] = m
                cal[Calendar.DAY_OF_MONTH] = d
                hireDate = cal.timeInMillis
                updateDateFields()
            }, cal[Calendar.YEAR], cal[Calendar.MONTH], cal[Calendar.DAY_OF_MONTH]).show()
        }
        binding.etShiftStart.setOnClickListener { showTimePicker(binding.etShiftStart) }
        binding.etShiftEnd.setOnClickListener { showTimePicker(binding.etShiftEnd) }
    }

    private fun showTimePicker(editText: EditText) {
        val current = editText.text.toString()
        val parts = current.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 10
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        TimePickerDialog(requireContext(), { _, hourOfDay, minute ->
            editText.setText(String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute))
        }, h, m, true).show()
    }

    private fun updateDateFields() {
        binding.etDob.setText(dob?.let { dateFormat.format(Date(it)) } ?: "")
        binding.etHireDate.setText(dateFormat.format(Date(hireDate)))
    }

    override fun onStart() {
        super.onStart()
        // Removed deprecated SOFT_INPUT_ADJUST_RESIZE. 
        // Resizing is handled by the theme and Edge-to-Edge settings.
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
