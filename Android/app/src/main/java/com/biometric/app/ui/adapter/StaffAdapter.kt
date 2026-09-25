package com.biometric.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.data.entity.Employee
import com.biometric.app.databinding.ItemStaffCardBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class StaffAdapter(
    private val onCardClick: (Employee) -> Unit,
    private val onViewClick: (Employee) -> Unit,
    private val onEditClick: (Employee) -> Unit,
    private val onDeleteClick: (Employee) -> Unit
) : ListAdapter<Employee, StaffAdapter.StaffViewHolder>(EmployeeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StaffViewHolder {
        val binding = ItemStaffCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StaffViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StaffViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class StaffViewHolder(private val binding: ItemStaffCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(employee: Employee) {
            // # Employee ID
            val displayId = if (employee.employeeId.isNotBlank()) "#${employee.employeeId}" else "#--"
            binding.tvEmployeeIdBadge.text = displayId

            // Initial Avatar Circle
            val initial = if (employee.name.isNotBlank()) employee.name.take(1).uppercase(Locale.getDefault()) else "?"
            binding.tvAvatarInitial.text = initial

            // Name
            binding.tvStaffName.text = if (employee.isActive) employee.name else "🚫 ${employee.name} (Terminated)"

            // Biometric ID (🧬 1524 or 🧬 --)
            binding.tvBiometricIdBadge.text = if (employee.biometricId.isNotBlank()) "🧬 ${employee.biometricId}" else "🧬 --"

            // Role Badge
            binding.tvStaffRoleName.text = if (employee.role.isNotBlank()) employee.role else "Staff"

            // Pay: ₹15,000/mo or ₹.../hr
            val inrFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
                maximumFractionDigits = 0
            }
            val isHourly = employee.salaryType.equals("HOURLY", ignoreCase = true)
            val isDaily = employee.salaryType.equals("DAILY_WAGE", ignoreCase = true) || employee.salaryType.equals("PER_DAY", ignoreCase = true)
            val paySuffix = when {
                isHourly -> "/hr"
                isDaily -> "/day"
                else -> "/mo"
            }
            binding.tvPay.text = "${inrFormat.format(employee.salaryRate)}$paySuffix"

            // Hire Date / Term Date
            val sdf = SimpleDateFormat("dd-MMM-yy", Locale.getDefault())
            val hireStr = if (employee.hireDate > 0) "🗓️ ${sdf.format(Date(employee.hireDate))}" else "🗓️ --"
            val termStr = if (employee.terminateDate != null && employee.terminateDate!! > 0) {
                " • 🔴 Term: ${sdf.format(Date(employee.terminateDate!!))}"
            } else ""
            binding.tvHireDate.text = "$hireStr$termStr"

            // Shift Time (10:30 am – 04:30 pm)
            val shiftStartFmt = formatTimeAmPm(employee.shiftStart)
            val shiftEndFmt = formatTimeAmPm(employee.shiftEnd)
            binding.tvShiftTime.text = "⏰ $shiftStartFmt – $shiftEndFmt"

            // Break & Shift Mode (☕ 60 min break • Single Day)
            val breakMinutes = (employee.breakHours * 60).toInt()
            val shiftModeStr = if (employee.shiftMode.equals("CONTINUOUS", ignoreCase = true)) "Continuous" else "Single Day"
            binding.tvBreakAndMode.text = "☕ ${breakMinutes}m break • $shiftModeStr"

            // Off & Overtime (🏖️ Sunday • ⚡ 1.0x)
            val compOffStr = formatDayOfWeek(employee.compOffDayOfWeek)
            val otStr = if (employee.otRule.isNotBlank()) employee.otRule else "1.0x"
            binding.tvOffAndOt.text = "🏖️ $compOffStr • ⚡ $otStr"

            // Tracking Mode (🛰️ 24/7 Tracking vs 🛰️ Shift Time Only)
            val isShiftTracking = employee.trackingMode.trim().uppercase().contains("SHIFT")
            binding.tvTrackingMode.text = if (isShiftTracking) "🛰️ Shift Time Only" else "🛰️ 24/7 Tracking"

            // Click Actions
            binding.root.setOnClickListener { onCardClick(employee) }
            binding.btnViewRecords.setOnClickListener { onViewClick(employee) }
            binding.btnEditStaff.setOnClickListener { onEditClick(employee) }
            binding.btnDeleteStaff.setOnClickListener { onDeleteClick(employee) }
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
    }

    class EmployeeDiffCallback : DiffUtil.ItemCallback<Employee>() {
        override fun areItemsTheSame(oldItem: Employee, newItem: Employee): Boolean {
            return oldItem.employeeId == newItem.employeeId
        }

        override fun areContentsTheSame(oldItem: Employee, newItem: Employee): Boolean {
            return oldItem == newItem
        }
    }
}
