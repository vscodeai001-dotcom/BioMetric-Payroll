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

    private var startDate: Long = System.currentTimeMillis()
    private var endDate: Long = System.currentTimeMillis()
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentApplyLeaveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupForm()
    }

    private fun setupForm() {
        updateDateRangeDisplay()
        
        binding.etDateRange.setOnClickListener {
            val picker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText("Select Leave Range")
                .setSelection(Pair(startDate, endDate))
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                startDate = selection.first ?: startDate
                endDate = selection.second ?: endDate
                updateDateRangeDisplay()
            }
            picker.show(parentFragmentManager, "leave_date_range")
        }

        val leaveTypes = arrayOf("Sick Leave", "Casual Leave", "Privilege Leave", "Leave Without Pay")
        val adapter = ArrayAdapter(requireContext(), R.layout.simple_dropdown_item_1line, leaveTypes)
        binding.actvLeaveType.setAdapter(adapter)

        binding.btnSubmit.setOnClickListener {
            submitLeave()
        }
    }

    private fun updateDateRangeDisplay() {
        val startStr = sdf.format(Date(startDate))
        val endStr = sdf.format(Date(endDate))
        binding.etDateRange.setText("$startStr to $endStr")
    }

    private fun submitLeave() {
        val type = _binding?.actvLeaveType?.text?.toString().orEmpty()
        val isHalfDay = _binding?.switchHalfDay?.isChecked ?: false
        val notes = _binding?.etNotes?.text?.toString().orEmpty()
        if (type.isBlank()) {
            if (isAdded) Toast.makeText(requireContext(), "Please select leave type ⚠️", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val daysCount = ((endDate - startDate) / (1000L * 60 * 60 * 24)).toInt() + 1
                repeat(daysCount) { i ->
                    val dateStr = sdf.format(Date(startDate + i * 1000L * 60 * 60 * 24))
                    selfService.createLeave(dateStr, type, isHalfDay, notes)
                }
                Toast.makeText(requireContext(), "$daysCount days applied successfully! 🌴 💎 ✅", Toast.LENGTH_SHORT).show()
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
