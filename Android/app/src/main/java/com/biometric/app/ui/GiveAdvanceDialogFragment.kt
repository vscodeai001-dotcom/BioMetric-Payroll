package com.biometric.app.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.biometric.app.ui.viewmodel.StaffViewModel
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.MotionManager
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class GiveAdvanceDialogFragment : DialogFragment() {

    private val viewModel: StaffViewModel by activityViewModels()
    private val calendar = Calendar.getInstance()

    companion object {
        const val TAG = "GiveAdvanceDialog"
        private const val ARG_EMPLOYEE_ID = "employeeId"

        fun newInstance(employeeId: String): GiveAdvanceDialogFragment {
            val args = Bundle().apply {
                putString(ARG_EMPLOYEE_ID, employeeId)
            }
            return GiveAdvanceDialogFragment().apply {
                arguments = args
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val employeeId = requireArguments().getString(ARG_EMPLOYEE_ID)!!

        val amountInput = EditText(requireContext()).apply {
            hint = "Advance Amount (₹)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val dateTextView = TextView(requireContext()).apply {
            text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(calendar.time)
            textSize = 18f
        }

        val datePickerButton = Button(requireContext()).apply {
            text = "Select Date"
        }

        val linearLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(amountInput)
            addView(dateTextView)
            addView(datePickerButton)
        }

        datePickerButton.setOnClickListener {
            showDatePickerDialog(dateTextView)
        }

        val dialog = AlertDialog.Builder(requireActivity())
            .setTitle("Give Advance")
            .setView(linearLayout)
            .setPositiveButton("Pay") { _, _ ->
                val amount = amountInput.text.toString().toDoubleOrNull() ?: 0.0
                if (amount > 0) {
                    viewModel.giveAdvance(employeeId, amount, calendar.time)
                    HapticUtil.vibrateSuccess(linearLayout)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            MotionManager.applyDialogAnimation(dialog)
            val payBtn = (it as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelBtn = it.getButton(AlertDialog.BUTTON_NEGATIVE)
            MotionManager.applyTouchScale(payBtn)
            MotionManager.applyTouchScale(cancelBtn)
        }

        MotionManager.applyTouchScale(datePickerButton)

        return dialog
    }

    private fun showDatePickerDialog(dateTextView: TextView) {
        val datePickerDialog = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                dateTextView.text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(calendar.time)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        datePickerDialog.show()
    }
}
