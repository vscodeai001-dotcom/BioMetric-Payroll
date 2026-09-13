package com.biometric.app.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.biometric.app.data.entity.Attendance
import com.biometric.app.data.entity.Employee
import com.biometric.app.databinding.DialogManualAttendanceBinding
import com.biometric.app.ui.viewmodel.StaffViewModel
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.MotionManager
import java.text.SimpleDateFormat
import java.util.*

class ManualAttendanceDialogFragment : DialogFragment() {

    private var _binding: DialogManualAttendanceBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: StaffViewModel
    private var selectedDate = Calendar.getInstance()
    private var inCal: Calendar? = null
    private var outCal: Calendar? = null
    private var existingRecord: Attendance? = null
    private var isEditMode = false

    companion object {
        const val TAG = "ManualAttendanceDialog"
        private const val ARG_EMPLOYEE = "employee"
        private const val ARG_ATTENDANCE = "attendance"

        fun newInstance(employee: Employee, attendance: Attendance? = null): ManualAttendanceDialogFragment {
            val args = Bundle().apply {
                putSerializable(ARG_EMPLOYEE, employee)
                if (attendance != null) putSerializable(ARG_ATTENDANCE, attendance)
            }
            return ManualAttendanceDialogFragment().apply {
                arguments = args
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[StaffViewModel::class.java]
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogManualAttendanceBinding.inflate(layoutInflater)
        @Suppress("DEPRECATION")
        val employee = requireArguments().getSerializable(ARG_EMPLOYEE) as Employee
        @Suppress("DEPRECATION")
        val attendanceToEdit = requireArguments().getSerializable(ARG_ATTENDANCE) as? Attendance

        if (attendanceToEdit != null) {
            isEditMode = true
            existingRecord = attendanceToEdit
            selectedDate.timeInMillis = attendanceToEdit.checkInTime
        }

        setupListeners()
        updateUIForRecord()

        val builder = AlertDialog.Builder(requireActivity())
            .setTitle(if (isEditMode) "Edit Attendance Entry" else "Attendance: ${employee.name}")
            .setView(binding.root)
            .setPositiveButton("Save") { _, _ ->
                saveEntry(employee)
            }
            .setNegativeButton("Cancel", null)

        if (isEditMode) {
            builder.setNeutralButton("Delete") { _, _ ->
                existingRecord?.let {
                    viewModel.deleteAttendance(it)
                    HapticUtil.vibrateSuccess(binding.root)
                    Toast.makeText(context, "Entry Deleted", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val dialog = builder.create()
        
        dialog.setOnShowListener {
            MotionManager.applyDialogAnimation(dialog)
            val saveBtn = (it as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelBtn = it.getButton(AlertDialog.BUTTON_NEGATIVE)
            val deleteBtn = it.getButton(AlertDialog.BUTTON_NEUTRAL)
            
            MotionManager.applyTouchScale(saveBtn)
            MotionManager.applyTouchScale(cancelBtn)
            deleteBtn?.let { btn -> MotionManager.applyTouchScale(btn) }
        }

        // Apply motion feedback to dialog internal controls
        MotionManager.applyTouchScale(binding.btnDate)
        MotionManager.applyTouchScale(binding.btnPrevDate)
        MotionManager.applyTouchScale(binding.btnNextDate)
        MotionManager.applyTouchScale(binding.btnInTime)
        MotionManager.applyTouchScale(binding.btnOutTime)

        return dialog
    }

    private fun setupListeners() {
        binding.btnDate.setOnClickListener {
            DatePickerDialog(requireContext(), { _, y, m, d ->
                selectedDate.set(y, m, d)
                updateDateButton()
            }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)).show()
        }

        binding.btnPrevDate.setOnClickListener {
            selectedDate.add(Calendar.DAY_OF_MONTH, -1)
            updateDateButton()
        }

        binding.btnNextDate.setOnClickListener {
            selectedDate.add(Calendar.DAY_OF_MONTH, 1)
            updateDateButton()
        }

        binding.btnInTime.setOnClickListener { showInTimePicker() }

        binding.btnOutTime.setOnClickListener {
            if (inCal == null) {
                Toast.makeText(context, "Set IN time first", Toast.LENGTH_SHORT).show()
            } else {
                showOutTimePicker()
            }
        }
    }

    private fun updateDateButton() {
        val df = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        binding.btnDate.text = df.format(selectedDate.time)
    }

    private fun updateUIForRecord() {
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        updateDateButton()

        if (existingRecord != null) {
            inCal = Calendar.getInstance().apply { timeInMillis = existingRecord!!.checkInTime }
            binding.btnInTime.text = "IN: ${tf.format(inCal!!.time)}"

            if (existingRecord!!.type == "GAP") binding.rbGap.isChecked = true
            else binding.rbWork.isChecked = true

            if (existingRecord!!.checkOutTime != null && existingRecord!!.checkOutTime!! > 0) {
                outCal = Calendar.getInstance().apply { timeInMillis = existingRecord!!.checkOutTime!! }
                binding.btnOutTime.text = "OUT: ${tf.format(outCal!!.time)}"
            } else {
                outCal = null
                binding.btnOutTime.text = "Select OUT Time"
            }

            // Always enable editing in edit mode
            binding.btnDate.isEnabled = true
            binding.btnInTime.isEnabled = true
            binding.btnOutTime.isEnabled = true
            binding.rbWork.isEnabled = true
            binding.rbGap.isEnabled = true

        } else {
            // New entry logic
            binding.btnInTime.isEnabled = true
            binding.btnOutTime.isEnabled = true
            binding.btnDate.isEnabled = true
            binding.rbWork.isEnabled = true
            binding.rbGap.isEnabled = true
        }
        updateDurationText()
    }

    private fun showInTimePicker() {
        TimePickerDialog(requireContext(), { _, h, m ->
            inCal = (selectedDate.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
            }
            binding.btnInTime.text = "IN: %02d:%02d".format(h, m)
            updateDurationText()
        }, inCal?.get(Calendar.HOUR_OF_DAY) ?: 10, inCal?.get(Calendar.MINUTE) ?: 0, false).show()
    }

    private fun showOutTimePicker() {
        TimePickerDialog(requireContext(), { _, h, m ->
            outCal = (selectedDate.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
            }
            if (inCal != null && outCal!!.timeInMillis < inCal!!.timeInMillis) {
                outCal!!.add(Calendar.DATE, 1)
            }
            binding.btnOutTime.text = "OUT: %02d:%02d".format(h, m)
            updateDurationText()
        }, outCal?.get(Calendar.HOUR_OF_DAY) ?: 17, outCal?.get(Calendar.MINUTE) ?: 30, false).show()
    }

    private fun updateDurationText() {
        if (inCal != null) {
            val endTime = outCal?.timeInMillis ?: System.currentTimeMillis()
            val diff = endTime - inCal!!.timeInMillis
            if (diff >= 0) {
                val hours = diff / (1000 * 60 * 60)
                val mins = (diff / (1000 * 60)) % 60
                binding.tvDuration.text = "Total: $hours hrs $mins mins"
                if (outCal == null) binding.tvDuration.text = binding.tvDuration.text.toString() + " (Running)"
            } else {
                binding.tvDuration.text = "Invalid Time Range"
            }
        } else {
            binding.tvDuration.text = "Total: 0 hrs 0 mins"
        }
    }

    private fun saveEntry(employee: Employee) {
        if (inCal == null) {
            Toast.makeText(context, "IN time is required to save", Toast.LENGTH_SHORT).show()
            return
        }

        val finalIn = inCal!!.timeInMillis
        val finalOut = outCal?.timeInMillis ?: 0L
        val isGap = binding.rbGap.isChecked

        if (isEditMode && existingRecord != null) {
            val endTime = if (finalOut > 0) finalOut else null
            val durationHours = if (endTime != null) (endTime - finalIn).toDouble() / (1000 * 60 * 60) else 0.0

            viewModel.updateAttendance(existingRecord!!.copy(
                checkInTime = finalIn,
                checkOutTime = endTime,
                hoursWorked = if (isGap) -durationHours else durationHours,
                type = if (isGap) "GAP" else "WORK"
            ))
            HapticUtil.vibrateSuccess(binding.root)
            Toast.makeText(context, "Entry Updated", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.addManualAttendanceEntry(employee, finalIn, finalOut, isGap)
            HapticUtil.vibrateSuccess(binding.root)
            Toast.makeText(context, "Entry Saved", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
