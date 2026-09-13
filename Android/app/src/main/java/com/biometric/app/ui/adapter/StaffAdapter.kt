package com.biometric.app.ui.adapter

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.EmployeeStats
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.EmployeeHistory
import com.biometric.app.databinding.ItemStaffCardBinding
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.Locale

class StaffAdapter(
    private val onAttendanceClick: (Employee) -> Unit,
    private val onAdvanceClick: (Employee) -> Unit,
    private val onAdvanceLogsClick: (Employee) -> Unit,
    private val onSalaryClick: (Employee) -> Unit,
    private val onViewLogsClick: (Employee) -> Unit,
    private val onSalaryHikeClick: (Employee) -> Unit,
    private val onShiftUpdateClick: (Employee) -> Unit,
    private val onAllowanceHikeClick: (Employee) -> Unit,
    private val onEditClick: (Employee) -> Unit,
    private val onDeleteClick: (Employee) -> Unit,
    private val onEditHistoryClick: (EmployeeHistory) -> Unit,
    private val onDeleteHistoryClick: (EmployeeHistory) -> Unit,
    private val onOverrideToggle: (Employee, String, String, Boolean) -> Unit,
    private val onShareClick: (View, Employee, List<View>, String?) -> Unit,
) : ListAdapter<Employee, StaffAdapter.StaffViewHolder>(EmployeeDiffCallback()) {

    private var statsMap: Map<String, EmployeeStats> = emptyMap()
    private var historyMap: Map<String, List<EmployeeHistory>> = emptyMap()
    private var daysInMonth: Int = Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
    private var currentMonthKey: String = ""
    private val expandedItems = mutableSetOf<String>()
    private val expandedCards = mutableSetOf<String>()

    init {
        val cal = Calendar.getInstance()
        currentMonthKey = "${cal[Calendar.YEAR]}_${cal[Calendar.MONTH] + 1}"
    }

    companion object {
        const val PAYLOAD_STATS = "PAYLOAD_STATS"
        const val PAYLOAD_HISTORY = "PAYLOAD_HISTORY"
        const val PAYLOAD_EXPANSION = "PAYLOAD_EXPANSION"
    }

    fun updateStats(newStats: Map<String, EmployeeStats>, days: Int? = null, monthKey: String? = null) {
        val oldStats = statsMap
        statsMap = newStats
        days?.let { daysInMonth = it }
        monthKey?.let { currentMonthKey = it }
        
        currentList.forEachIndexed { index, employee ->
            if (oldStats[employee.employeeId] != newStats[employee.employeeId]) {
                notifyItemChanged(index, PAYLOAD_STATS)
            }
        }
    }

    fun updateHistory(newHistory: Map<String, List<EmployeeHistory>>) {
        historyMap = newHistory
        currentList.forEachIndexed { index, _ ->
            notifyItemChanged(index, PAYLOAD_HISTORY)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StaffViewHolder {
        val binding = ItemStaffCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StaffViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StaffViewHolder, position: Int) {
        holder.bind(getItem(position), statsMap[getItem(position).employeeId])
    }

    override fun onBindViewHolder(holder: StaffViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val employee = getItem(position)
            val stats = statsMap[employee.employeeId]
            payloads.forEach { payload ->
                when (payload) {
                    PAYLOAD_STATS -> holder.updateStats(employee, stats)
                    PAYLOAD_HISTORY -> holder.updateHistory(employee)
                    PAYLOAD_EXPANSION -> holder.updateExpansion(employee)
                }
            }
        }
    }

    inner class StaffViewHolder(private val binding: ItemStaffCardBinding) :
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(employee: Employee, stats: EmployeeStats?) {
            binding.tvStaffName.text = if (employee.isActive) employee.name else "🚫 ${employee.name} (Terminated)"
            binding.tvStaffRoleName.text = "👔 ${employee.role}"
            binding.tvBiometricIdBadge.text = String.format(Locale.getDefault(), "🆔 ID: %s", employee.biometricId)
            binding.tvBiometricIdBadge.visibility = if (employee.biometricId.isNotEmpty()) View.VISIBLE else View.GONE
            
            updateStats(employee, stats)
            updateHistory(employee)
            updateExpansion(employee)

            binding.btnQuickAttendance.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onAttendanceClick(employee)
            }
            binding.btnAdvance.setOnClickListener { onAdvanceClick(employee) }
            binding.tvPendingAdvance.setOnClickListener { onAdvanceLogsClick(employee) }
            binding.btnPaySalary.setOnClickListener { onSalaryClick(employee) }
            binding.btnViewRecords.setOnClickListener { onViewLogsClick(employee) }
            binding.btnSalaryHike.setOnClickListener { onSalaryHikeClick(employee) }
            binding.btnShiftUpdate.setOnClickListener { onShiftUpdateClick(employee) }
            binding.btnAllowanceUpdate.setOnClickListener { onAllowanceHikeClick(employee) }
            binding.btnShare.setOnClickListener { 
                val viewsToHide = listOf(binding.llActionButtons, binding.btnToggleHistory, binding.llHistoryTimeline)
                onShareClick(binding.containerContent, employee, viewsToHide, null)
            }

            binding.btnToggleHistory.setOnClickListener {
                if (expandedItems.contains(employee.employeeId)) {
                    expandedItems.remove(employee.employeeId)
                } else {
                    expandedItems.add(employee.employeeId)
                }
                updateHistory(employee)
            }

            binding.tvStaffName.setOnClickListener {
                onEditClick(employee)
            }

            var lastClickTime = 0L
            binding.root.setOnClickListener { 
                val currentTime = System.currentTimeMillis()
                if ((currentTime - lastClickTime) < 400) { // 400ms for double click
                    onDeleteClick(employee)
                } else {
                    if (expandedCards.contains(employee.employeeId)) {
                        expandedCards.remove(employee.employeeId)
                    } else {
                        expandedCards.add(employee.employeeId)
                    }
                    notifyItemChanged(bindingAdapterPosition, PAYLOAD_EXPANSION)
                }
                lastClickTime = currentTime
            }
            binding.root.setOnLongClickListener {
                onEditClick(employee)
                true
            }
        }

        fun updateExpansion(employee: Employee) {
            val isExpanded = expandedCards.contains(employee.employeeId)
            binding.llBodyContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
            binding.dividerHeader.visibility = if (isExpanded) View.VISIBLE else View.GONE
        }

        fun updateHistory(employee: Employee) {
            val isExpanded = expandedItems.contains(employee.employeeId)
            binding.llHistoryTimeline.visibility = if (isExpanded) View.VISIBLE else View.GONE
            binding.dividerHistory.visibility = if (isExpanded) View.VISIBLE else View.GONE
            binding.btnToggleHistory.text = if (isExpanded) "Hide Change History ▲" else "View Change History ▼"

            if (isExpanded) {
                binding.llHistoryTimeline.removeAllViews()
                val history = historyMap[employee.employeeId] ?: emptyList()
                if (history.isEmpty()) {
                    val tv = TextView(itemView.context).apply {
                        text = context.getString(R.string.no_history_available)
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                        setPadding(16, 8, 16, 8)
                    }
                    binding.llHistoryTimeline.addView(tv)
                } else {
                    val sdf = java.text.SimpleDateFormat("dd MMM", Locale.getDefault())
                    history.forEach { item ->
                        val row = LayoutInflater.from(itemView.context).inflate(R.layout.item_history_row, binding.llHistoryTimeline, false)
                        val tvIcon = row.findViewById<TextView>(R.id.tvHistoryIcon)
                        val tvTitle = row.findViewById<TextView>(R.id.tvHistoryTitle)
                        val tvReason = row.findViewById<TextView>(R.id.tvHistoryReason)
                        val tvDate = row.findViewById<TextView>(R.id.tvHistoryDate)
                        val btnDelete = row.findViewById<View>(R.id.btnDeleteHistory)

                        btnDelete.visibility = View.GONE

                        tvIcon.text = when (item.type) {
                            "SALARY" -> "💰"
                            "ALLOWANCE" -> "🎁"
                            "SHIFT" -> "🕒"
                            else -> "❓"
                        }
                        
                        val typeLabel = when (item.type) {
                            "SALARY" -> "Salary"
                            "ALLOWANCE" -> "Allow."
                            "SHIFT" -> "Shift"
                            else -> "Record"
                        }
                        
                        tvTitle.text = when (item.type) {
                            "SHIFT" -> {
                                val netHrs = getShiftHours(item.shiftStart, item.shiftEnd, item.breakHours, item.shift2Start, item.shift2End)
                                if (item.shift2Start != null) {
                                    String.format(
                                        Locale.getDefault(), "Split Shift: %s-%s & %s-%s (%.1fh)",
                                        item.shiftStart, item.shiftEnd, item.shift2Start, item.shift2End, netHrs
                                    )
                                } else {
                                    String.format(
                                        Locale.getDefault(), "Shift: %s - %s (%.1fh)",
                                        item.shiftStart, item.shiftEnd, netHrs
                                    )
                                }
                            }
                            else -> {
                                if ((item.oldValue > 0) || (item.version > 1)) {
                                    String.format(Locale.getDefault(), "%s: ₹%,.0f ➔ ₹%,.0f", typeLabel, item.oldValue, item.newValue)
                                } else {
                                    String.format(Locale.getDefault(), "%s: ₹%,.0f (Initial)", typeLabel, item.newValue)
                                }
                            }
                        }

                        if (!item.changeReason.isNullOrBlank()) {
                            tvReason.visibility = View.VISIBLE
                            tvReason.text = item.changeReason
                        }
                        
                        val dateStr = if (item.endDate != null) {
                            String.format(Locale.getDefault(), "%s - %s", sdf.format(java.util.Date(item.effectiveDate)), sdf.format(java.util.Date(item.endDate!!)))
                        } else {
                            String.format(Locale.getDefault(), "From: %s", sdf.format(java.util.Date(item.effectiveDate)))
                        }
                        tvDate.text = dateStr
                        
                        row.setOnClickListener {
                            onEditHistoryClick(item)
                        }
                        row.setOnLongClickListener {
                            onDeleteHistoryClick(item)
                            true
                        }
                        
                        binding.llHistoryTimeline.addView(row)
                    }
                }
            }
        }

        fun updateStats(employee: Employee, stats: EmployeeStats?) {
            val context = itemView.context

            // --- HEADER REFRESH ---
            val salaryType = stats?.salaryType ?: employee.salaryType
            val salaryRate = if (stats != null && (stats.selectedDaySalaryRate > 0)) {
                stats.selectedDaySalaryRate
            } else {
                employee.salaryRate
            }
            
            val netHrs = if (stats != null && stats.netShiftHours > 0) {
                stats.netShiftHours
            } else {
                val isWeekendSelected = if (stats?.selectedDayMidnight != null && stats.selectedDayMidnight > 0) {
                    val cal = Calendar.getInstance().apply { timeInMillis = stats.selectedDayMidnight }
                    cal[Calendar.DAY_OF_WEEK] == Calendar.SATURDAY || cal[Calendar.DAY_OF_WEEK] == Calendar.SUNDAY
                } else false

                val currentStart = if (isWeekendSelected && employee.weekendShiftStart != null) employee.weekendShiftStart!! else employee.shiftStart
                val currentEnd = if (isWeekendSelected && employee.weekendShiftEnd != null) employee.weekendShiftEnd!! else employee.shiftEnd
                val curS2Start = if (isWeekendSelected && employee.weekendShift2Start != null) employee.weekendShift2Start else employee.shift2Start
                val curS2End = if (isWeekendSelected && employee.weekendShift2End != null) employee.weekendShift2End else employee.shift2End
                val currentBreak = if (isWeekendSelected && employee.weekendBreakHours != null) employee.weekendBreakHours!! else employee.breakHours

                getShiftHours(currentStart, currentEnd, currentBreak, curS2Start, curS2End)
            }

            val salaryTypeText = if (salaryType == "MONTHLY_FIXED") "Monthly 💰" else "Hourly ⏱️"
            
            binding.tvStaffRole.text = String.format(
                Locale.getDefault(),
                "%s | ₹%.0f | Shift: %.1fh",
                salaryTypeText,
                salaryRate,
                netHrs
            )

            // Update Presence Anim based on status
            val isPresent = stats?.isCheckedIn ?: false
            if (isPresent) {
                binding.animStaffPresence.setAnimation(R.raw.anim_ai_spark)
                binding.animStaffPresence.playAnimation()
                binding.animStaffPresence.alpha = 1.0f
            } else {
                binding.animStaffPresence.pauseAnimation()
                binding.animStaffPresence.alpha = 0.2f
            }

            // Update Checkboxes
            binding.cbIncludeBonus.setOnCheckedChangeListener(null)
            binding.cbIncludePaidLeave.setOnCheckedChangeListener(null)
            
            val isBonusEligible = stats?.isBonusEligible ?: false
            val isPLEligible = stats?.isPaidLeaveEligible ?: false
            val isAutoBonusEligible = stats?.isBonusEligibleByRule ?: false
            val plUsedHrs = stats?.paidLeaveAppliedHours ?: 0.0

            binding.cbIncludeBonus.isChecked = isBonusEligible
            binding.cbIncludePaidLeave.isChecked = isPLEligible

            val isSystematicPLUsed = plUsedHrs > 0.001
            val bonusEnabled: Boolean
            val plEnabled: Boolean

            when {
                isSystematicPLUsed -> { bonusEnabled = false; plEnabled = false }
                isAutoBonusEligible -> { bonusEnabled = false; plEnabled = true }
                else -> { bonusEnabled = true; plEnabled = true }
            }

            binding.cbIncludeBonus.isEnabled = bonusEnabled
            binding.cbIncludePaidLeave.isEnabled = plEnabled
            binding.cbIncludeBonus.alpha = if (bonusEnabled) 1.0f else 0.4f
            binding.cbIncludePaidLeave.alpha = if (plEnabled) 1.0f else 0.4f

            binding.cbIncludeBonus.setOnCheckedChangeListener { _, isChecked ->
                onOverrideToggle(employee, currentMonthKey, "BONUS", isChecked)
            }
            binding.cbIncludePaidLeave.setOnCheckedChangeListener { _, isChecked ->
                onOverrideToggle(employee, currentMonthKey, "PAID_LEAVE", isChecked)
            }

            // Daily Stats for header
            val selDayNormalSalary = stats?.selectedDayNormalWorkedSalary ?: BigDecimal.ZERO
            val selDayOTSalary = stats?.selectedDayOTSalary ?: BigDecimal.ZERO
            val totalDaySalary = selDayNormalSalary.add(selDayOTSalary)
            val totalDayHours = stats?.selectedDayWorkedHours ?: 0.0

            // Binding values to UI header
            binding.tvTodayStats.text = String.format(Locale.getDefault(), "📅 Worked: %.1fh | ₹%.2f", totalDayHours, totalDaySalary.toDouble())
            
            // Default Section
            val defaultPerDay = if (salaryType == "MONTHLY_FIXED") {
                salaryRate / daysInMonth.toDouble()
            } else {
                salaryRate * (if (netHrs > 0) netHrs else 0.0)
            }
            
            val defaultPerHour = if (salaryType == "MONTHLY_FIXED") {
                if (netHrs > 0) defaultPerDay / netHrs else 0.0
            } else {
                salaryRate
            }

            binding.tvRequiredHours.text = String.format(Locale.getDefault(), "%.0f hrs", stats?.requiredWorkHours ?: 0.0)
            binding.tvDefaultPerDay.text = String.format(Locale.getDefault(), "₹ %.2f", defaultPerDay)
            binding.tvDefaultPerHour.text = String.format(Locale.getDefault(), "₹ %.2f", defaultPerHour)
            
            // Earned Salary Info Section
            val workHr = stats?.totalNormalWorkedHoursMonth ?: 0.0
            val workSal = stats?.totalNormalWorkedSalaryMonth ?: BigDecimal.ZERO
            val otHr = stats?.totalOTHoursMonth ?: 0.0
            val otSal = stats?.totalOTSalaryMonth ?: BigDecimal.ZERO
            val plHr = stats?.paidLeaveAppliedHours ?: 0.0
            val plSal = stats?.paidLeaveSalary ?: BigDecimal.ZERO
            
            binding.tvTotalNormalHours.text = String.format(Locale.getDefault(), "%.1f h", workHr)
            binding.tvTotalNormalSalary.text = String.format(Locale.getDefault(), "₹%.0f", workSal.toDouble())
            binding.tvTotalOTHours.text = String.format(Locale.getDefault(), "%.1f h", otHr)
            binding.tvTotalOTSalary.text = String.format(Locale.getDefault(), "₹%.0f", otSal.toDouble())
            
            val showPL = isPLEligible || plHr > 0
            binding.llPLHrsContainer.visibility = if (showPL) View.VISIBLE else View.GONE
            binding.plPlaceholder.visibility = if (showPL) View.GONE else View.VISIBLE
            
            if (showPL) {
                binding.tvEarnedPLHrs.text = String.format(Locale.getDefault(), "%.1f h", plHr)
                binding.tvEarnedPLSalary.text = String.format(Locale.getDefault(), "₹%.0f", plSal.toDouble())
            }

            val earnedNetHr = workHr + otHr + plHr
            val earnedNetSal = workSal.add(otSal).add(plSal).setScale(0, RoundingMode.HALF_UP)
            binding.tvEarnedNetWorkedHrs.text = String.format(Locale.getDefault(), "%.1f h", earnedNetHr)
            binding.tvEarnedNetWorkedSalary.text = String.format(Locale.getDefault(), "₹%.0f", earnedNetSal.toDouble())

            binding.tvDaysPresent.text = "✅ ${(stats?.daysPresent ?: 0)}"
            binding.tvDaysAbsent.text = "❌ ${(stats?.daysAbsent ?: 0)}"
            binding.tvNetWorkedHrs.text = "⏱️ ${String.format(Locale.getDefault(), "%.1f h", earnedNetHr)}"
            binding.tvDeductibleHours.text = "📉 ${String.format(Locale.getDefault(), "%.1f h", stats?.shortfallHours ?: 0.0)}"
            binding.tvDeductibleDays.text = "🗓️ ${String.format(Locale.getDefault(), "%.1f Days", stats?.shortfallDays ?: 0.0)}"
            binding.tvDeductibleAmount.text = "💸 ${String.format(Locale.getDefault(), "₹ %.0f", stats?.deductibleAmount?.toDouble() ?: 0.0)}"

            val deductTooltip = ("Weekdays: %.1fh (₹%.0f)\nWeekends: %.1fh (₹%.0f)")
                .format(
                    stats?.weekdayDeductibleHours ?: 0.0, 
                    stats?.weekdayDeductibleAmount?.toDouble() ?: 0.0,
                    stats?.weekendDeductibleHours ?: 0.0, 
                    stats?.weekendDeductibleAmount?.toDouble() ?: 0.0
                )
            
            binding.tvDeductibleHours.tooltipText = deductTooltip
            binding.tvDeductibleDays.tooltipText = "Calculated based on shift hours"
            binding.tvDeductibleAmount.tooltipText = deductTooltip

            binding.tvWeekdayShortfall.text = String.format(
                Locale.getDefault(), "🗓️ Wk: %.1fh (₹%.0f)",
                stats?.weekdayDeductibleHours ?: 0.0,
                stats?.weekdayDeductibleAmount?.toDouble() ?: 0.0,
            )
            binding.tvWeekendShortfall.text = String.format(
                Locale.getDefault(), "🏖️ We: %.1fh (₹%.0f)",
                stats?.weekendDeductibleHours ?: 0.0,
                stats?.weekendDeductibleAmount?.toDouble() ?: 0.0,
            )
            
            if (plHr > 0) {
                binding.tvPaidLeaveStatus.text = "Paid Leave: Used 🌴"
                binding.tvPaidLeaveStatus.setTextColor(ContextCompat.getColor(context, R.color.green))
            } else {
                binding.tvPaidLeaveStatus.text = "Paid Leave: No 🌴"
                binding.tvPaidLeaveStatus.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
            
            binding.tvPendingAdvance.text = "₹ %.2f".format(stats?.monthlyAdvance ?: 0.0)
            
            val bonus = stats?.bonusAmount ?: BigDecimal.ZERO
            binding.tvBonusRecv.text = "₹ %.2f".format(bonus.toDouble())
            if (bonus > BigDecimal.ZERO) {
                binding.tvBonusRecv.setTextColor(ContextCompat.getColor(context, R.color.green))
                binding.tvBonusStatus.text = "Bonus: Yes 🎁"
                binding.tvBonusStatus.setTextColor(ContextCompat.getColor(context, R.color.green))
            } else {
                binding.tvBonusRecv.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                binding.tvBonusStatus.text = "Bonus: No 🎁"
                binding.tvBonusStatus.setTextColor(ContextCompat.getColor(context, R.color.red))
            }
            
            binding.tvAllowanceRecv.text = "₹ %.2f".format(stats?.monthlyAllowance?.toDouble() ?: 0.0)
            binding.btnAttendance.text = if (stats?.isCheckedIn == true) "CHECK OUT 🔴" else "ATTENDANCE 📅"
            
            updatePayableText(stats)
        }

        private fun updatePayableText(stats: EmployeeStats?) {
            if (stats == null) return
            val netPayable = stats.getNetPayableForStaffScreen()
            binding.tvCurrentSalary.text = String.format(Locale.getDefault(), "₹ %.2f", netPayable)
            val monthAdvance = stats.monthlyAdvance
            val currentPayable = netPayable - monthAdvance
            binding.tvCurrentPayable.text = String.format(Locale.getDefault(), "₹ %.2f", currentPayable)
        }
    }

    private fun getShiftHours(start: String, end: String, breakHrs: Double, start2: String? = null, end2: String? = null): Double {
        val sParts = start.split(":")
        val eParts = end.split(":")
        val startDecimal = (sParts.getOrNull(0)?.toInt() ?: 0) + (sParts.getOrNull(1)?.toInt() ?: 0) / 60.0
        var endDecimal = (eParts.getOrNull(0)?.toInt() ?: 0) + (eParts.getOrNull(1)?.toInt() ?: 0) / 60.0
        if (endDecimal < startDecimal) endDecimal += 24.0
        
        val shift1Hrs = endDecimal - startDecimal
        
        var shift2Hrs = 0.0
        if (start2 != null && end2 != null) {
            val s2Parts = start2.split(":")
            val e2Parts = end2.split(":")
            val s2Decimal = (s2Parts.getOrNull(0)?.toInt() ?: 0) + (s2Parts.getOrNull(1)?.toInt() ?: 0) / 60.0
            var e2Decimal = (e2Parts.getOrNull(0)?.toInt() ?: 0) + (e2Parts.getOrNull(1)?.toInt() ?: 0) / 60.0
            if (e2Decimal < s2Decimal) e2Decimal += 24.0
            shift2Hrs = e2Decimal - s2Decimal
        }
        
        return (shift1Hrs + shift2Hrs - breakHrs).coerceAtLeast(0.0)
    }

    class EmployeeDiffCallback : DiffUtil.ItemCallback<Employee>() {
        override fun areItemsTheSame(oldItem: Employee, newItem: Employee) = oldItem.employeeId == newItem.employeeId
        override fun areContentsTheSame(oldItem: Employee, newItem: Employee) = oldItem == newItem
    }
}
