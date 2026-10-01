package com.biometric.app.ui.selfservice

import android.R
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.util.Pair
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.biometric.app.databinding.FragmentApplyLeaveBinding
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import com.google.android.material.datepicker.MaterialDatePicker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class ApplyLeaveFragment : Fragment() {

    private var _binding: FragmentApplyLeaveBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var selfService: FirebaseEmployeeSelfServiceRepository

    private var isSingleDay: Boolean = true
    private var startDate: Long = getUtcToday()
    private var endDate: Long = getUtcToday()
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun getUtcToday(): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentApplyLeaveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupForm()
    }

    private fun setupForm() {
        updateDateDisplay()

        binding.toggleDurationType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isSingleDay = (checkedId == binding.btnSingleDay.id)
                if (isSingleDay) {
                    binding.tilDateRange.hint = "Select Date 📅"
                    endDate = startDate
                } else {
                    binding.tilDateRange.hint = "Select Date Range 🗓️"
                }
                updateDateDisplay()
            }
        }

        binding.etDateRange.setOnClickListener {
            if (isSingleDay) {
                val picker = MaterialDatePicker.Builder.datePicker()
                    .setTitleText("Select Leave Date")
                    .setSelection(startDate)
                    .build()
                picker.addOnPositiveButtonClickListener { selection ->
                    if (selection != null) {
                        startDate = selection
                        endDate = selection
                        updateDateDisplay()
                    }
                }
                picker.show(parentFragmentManager, "leave_single_date")
            } else {
                val picker = MaterialDatePicker.Builder.dateRangePicker()
                    .setTitleText("Select Leave Range")
                    .setSelection(Pair(startDate, endDate))
                    .build()
                picker.addOnPositiveButtonClickListener { selection ->
                    startDate = selection.first ?: startDate
                    endDate = selection.second ?: endDate
                    updateDateDisplay()
                }
                picker.show(parentFragmentManager, "leave_date_range")
            }
        }

        val leaveTypes = arrayOf("Sick Leave", "Casual Leave", "Privilege Leave", "Leave Without Pay")
        val adapter = ArrayAdapter(requireContext(), R.layout.simple_dropdown_item_1line, leaveTypes)
        binding.actvLeaveType.setAdapter(adapter)

        binding.btnSubmit.setOnClickListener {
            submitLeave()
        }
    }

    private fun updateDateDisplay() {
        val startStr = sdf.format(Date(startDate))
        if (isSingleDay || startDate == endDate) {
            binding.etDateRange.setText(startStr)
        } else {
            val endStr = sdf.format(Date(endDate))
            binding.etDateRange.setText("$startStr to $endStr")
        }
    }

    private fun submitLeave() {
        val type = _binding?.actvLeaveType?.text?.toString().orEmpty()
        val isHalfDay = _binding?.switchHalfDay?.isChecked ?: false
        val notes = _binding?.etNotes?.text?.toString().orEmpty()
        if (type.isBlank()) {
            if (isAdded) Toast.makeText(requireContext(), "Please select leave type ⚠️", Toast.LENGTH_SHORT).show()
            return
        }

        // Disable button immediately to prevent rapid multi-taps creating duplicate requests
        _binding?.btnSubmit?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var daysCount = 0
                if (isSingleDay || startDate == endDate) {
                    val dateStr = sdf.format(Date(startDate))
                    selfService.createLeave(dateStr, type, isHalfDay, notes)
                    daysCount = 1
                } else {
                    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = startDate
                    }
                    val endCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = endDate
                    }
                    while (!cal.after(endCal)) {
                        val dateStr = sdf.format(cal.time)
                        selfService.createLeave(dateStr, type, isHalfDay, notes)
                        daysCount++
                        cal.add(Calendar.DAY_OF_MONTH, 1)
                    }
                }
                val msg = if (daysCount == 1) "Leave applied successfully! 🌴 💎 ✅" else "$daysCount days applied successfully! 🌴 💎 ✅"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                activity?.onBackPressedDispatcher?.onBackPressed()
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(requireContext(), "Submission error: ${e.message ?: "Firebase unavailable"} ⚠️", Toast.LENGTH_SHORT).show()
                // Re-enable so the user can retry on error
                _binding?.btnSubmit?.isEnabled = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
