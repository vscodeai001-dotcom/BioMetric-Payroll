package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.biometric.app.api.RegularizationCreateRequest
import com.biometric.app.databinding.FragmentApplyRegularizationBinding
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import com.biometric.app.domain.audit.AuditLogger
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class ApplyRegularizationFragment : Fragment() {

    private var _binding: FragmentApplyRegularizationBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var selfService: FirebaseEmployeeSelfServiceRepository
    @Inject lateinit var auditLogger: AuditLogger

    private var selectedDate: Long = System.currentTimeMillis()
    private val dateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var selectedTime = "09:00"
    private var preselectedType: Boolean? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentApplyRegularizationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preselectedType = arguments?.getBoolean(ARG_IS_IN_PUNCH)
        setupForm()
    }

    private fun setupForm() {
        val presetDate = arguments?.getString(ARG_DATE)
        if (!presetDate.isNullOrBlank()) {
            try {
                selectedDate = dateSdf.parse(presetDate)?.time ?: selectedDate
            } catch (_: Exception) { }
        }
        binding.etDate.setText(dateSdf.format(Date(selectedDate)))
        binding.etDate.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Date of Punch")
                .setSelection(selectedDate)
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                selectedDate = selection ?: selectedDate
                binding.etDate.setText(dateSdf.format(Date(selectedDate)))
            }
            picker.show(parentFragmentManager, "punch_date")
        }

        preselectedType?.let {
            binding.btnIn.isChecked = it
            binding.btnOut.isChecked = !it
        }

        binding.etTime.setText(selectedTime)
        binding.etTime.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(9)
                .setMinute(0)
                .setTitleText("Select Punch Time")
                .build()
            picker.addOnPositiveButtonClickListener {
                selectedTime = String.format(Locale.US, "%02d:%02d", picker.hour, picker.minute)
                binding.etTime.setText(selectedTime)
            }
            picker.show(parentFragmentManager, "punch_time")
        }

        binding.btnSubmit.setOnClickListener {
            submitCorrection()
        }
    }

    private fun submitCorrection() {
        val date = _binding?.etDate?.text?.toString().orEmpty()
        val time = _binding?.etTime?.text?.toString().orEmpty()
        val isInPunch = _binding?.btnIn?.isChecked ?: true
        val reason = _binding?.etReason?.text?.toString().orEmpty()
        if (reason.isBlank()) {
            if (isAdded) Toast.makeText(requireContext(), "Please provide a reason ⚠️", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                selfService.createRegularization(
                    RegularizationCreateRequest(date, isInPunch, time, reason)
                )
                auditLogger.logAction(
                    shopId = null,
                    action = "CREATE",
                    module = "Regularization",
                    newValue = "date=$date,type=${if (isInPunch) "IN" else "OUT"},time=$time,reason=$reason"
                )
                Toast.makeText(requireContext(), "Correction request submitted! 🛠️ 💎 ✅", Toast.LENGTH_SHORT).show()
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

    companion object {
        private const val ARG_DATE = "date"
        private const val ARG_IS_IN_PUNCH = "is_in_punch"

        fun newInstance(date: String, isInPunch: Boolean): ApplyRegularizationFragment =
            ApplyRegularizationFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DATE, date)
                    putBoolean(ARG_IS_IN_PUNCH, isInPunch)
                }
            }
    }
}
