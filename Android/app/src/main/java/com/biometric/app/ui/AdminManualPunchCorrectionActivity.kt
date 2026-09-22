package com.biometric.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.data.entity.Employee
import com.biometric.app.databinding.ActivityAdminManualPunchCorrectionBinding
import com.biometric.app.databinding.ItemManualPunchDayBinding
import com.biometric.app.databinding.ItemPunchBubbleBinding
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.DateRangeUtil
import com.biometric.app.util.PremiumLoader
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class AdminManualPunchCorrectionActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityAdminManualPunchCorrectionBinding
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var attendanceSafety: com.biometric.app.data.repository.FirebaseAdminAttendanceRepository
    
    private var startDate: Calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
    private var endDate: Calendar = Calendar.getInstance()
    
    private val problematicDays = mutableListOf<ProblemDayInfo>()
    private lateinit var adapter: ProblemDayAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminManualPunchCorrectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        applyWindowInsets(binding.clManualPunchCorrectionRoot, binding.appBar)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.colorPrimary),
            ContextCompat.getColor(this, R.color.green_700)
        )
        binding.swipeRefresh.setOnRefreshListener {
            sharedViewModel.warmUpDashboard()
            loadIssues()
        }

        setupPickers()
        setupRecyclerView()
        observeData()

        binding.btnFindIssues.setOnClickListener {
            loadIssues()
        }

    }

    private fun setupPickers() {
        val sdf = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())
        binding.tvStartDate.text = sdf.format(startDate.time)
        binding.tvEndDate.text = sdf.format(endDate.time)

        binding.tvStartDate.setOnClickListener {
            showDatePicker(startDate) {
                binding.tvStartDate.text = sdf.format(it.time)
            }
        }
        binding.tvEndDate.setOnClickListener {
            showDatePicker(endDate) {
                binding.tvEndDate.text = sdf.format(it.time)
            }
        }
    }

    private fun showDatePicker(cal: Calendar, onSelected: (Calendar) -> Unit) {
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            onSelected(cal)
        }, cal[Calendar.YEAR], cal[Calendar.MONTH], cal[Calendar.DAY_OF_MONTH]).show()
    }

    private fun setupRecyclerView() {
        adapter = ProblemDayAdapter()
        binding.rvProblemDays.layoutManager = LinearLayoutManager(this)
        binding.rvProblemDays.adapter = adapter
    }

    private fun observeData() {
        lifecycleScope.launch {
            sharedViewModel.allEmployees.collectLatest { list ->
                val names = mutableListOf("All Employees")
                names.addAll(list.map { it.name })
                val spinnerAdapter = ArrayAdapter(this@AdminManualPunchCorrectionActivity, android.R.layout.simple_spinner_item, names)
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerEmployees.adapter = spinnerAdapter
            }
        }
        lifecycleScope.launch {
            sharedViewModel.allAttendancePunches.collectLatest {
                if (!isFinishing && !isDestroyed) loadIssues()
            }
        }
    }

    private fun loadIssues() {
        val allEmployees = sharedViewModel.allEmployees.value
        val selectedIdx = binding.spinnerEmployees.selectedItemPosition
        val targetEmployees = if (selectedIdx == 0) allEmployees else listOf(allEmployees[selectedIdx - 1])
        
        PremiumLoader.show(binding.loader, PremiumLoader.ScreenType.GENERIC, lifecycleScope)
        
        lifecycleScope.launch {
            // Requirement: Ensure SSOT alignment by performing a warm-up sync if needed.
            sharedViewModel.warmUpDashboard()
            delay(1000)

            val startMs = DateRangeUtil.getStartOfDay(startDate.timeInMillis)
            val endMs = DateRangeUtil.getEndOfDay(endDate.timeInMillis)
            
            // REQUIREMENT: High-accuracy finding of odd/missing punches using raw log SSOT
            val allPunches = sharedViewModel.allAttendancePunches.value.filter { it.timestamp in startMs..endMs }
            Log.d("PunchCorrection", "Scan started. Punches in range: ${allPunches.size}")

            val newProblems = mutableListOf<ProblemDayInfo>()
            
            val tempCal = startDate.clone() as Calendar
            while (tempCal.timeInMillis <= endDate.timeInMillis) {
                // Rule: Skip Sundays (or use company settings if available)
                if (tempCal[Calendar.DAY_OF_WEEK] != Calendar.SUNDAY) {
                    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(tempCal.time)
                    val displayDate = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(tempCal.time)
                    val dayStart = DateRangeUtil.getStartOfDay(tempCal.timeInMillis)
                    val dayEnd = DateRangeUtil.getEndOfDay(tempCal.timeInMillis)
                    
                    targetEmployees.forEach { emp ->
                        val dayPunches = allPunches.filter { it.staffId == emp.employeeId && it.timestamp in dayStart..dayEnd }
                            .sortedBy { it.timestamp }
                        
                        // Rule: Missing or Incomplete (Odd count)
                        if (dayPunches.isEmpty() || dayPunches.size % 2 != 0) {
                            Log.i("PunchCorrection", "Issue for ${emp.name} on $dateStr (Count: ${dayPunches.size})")
                            newProblems.add(ProblemDayInfo(
                                employee = emp,
                                date = tempCal.timeInMillis,
                                dateStr = dateStr,
                                displayDate = displayDate,
                                punches = dayPunches.toMutableList()
                            ))
                        }
                    }
                }
                tempCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            
            problematicDays.clear()
            problematicDays.addAll(newProblems)
            adapter.notifyDataSetChanged()
            
            binding.swipeRefresh.isRefreshing = false
            binding.llEmptyState.visibility = if (problematicDays.isEmpty()) View.VISIBLE else View.GONE
            PremiumLoader.hide(binding.loader)
        }
    }

    data class ProblemDayInfo(
        val employee: Employee,
        val date: Long,
        val dateStr: String,
        val displayDate: String,
        val punches: MutableList<AttendancePunch>
    )

    inner class ProblemDayAdapter : RecyclerView.Adapter<ProblemDayAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemManualPunchDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val day = problematicDays[position]
            holder.binding.tvEmployeeName.text = day.employee.name
            holder.binding.tvDateLabel.text = day.displayDate
            
            if (day.punches.isNotEmpty() && day.punches.size % 2 != 0) {
                holder.binding.tvOddCountBadge.visibility = View.VISIBLE
                holder.binding.tvOddCountBadge.text = "Odd Count (${day.punches.size})"
            } else {
                holder.binding.tvOddCountBadge.visibility = View.GONE
            }

            holder.binding.cgPunches.removeAllViews()
            if (day.punches.isEmpty()) {
                holder.binding.llEmptyPunches.visibility = View.VISIBLE
                holder.binding.btnQuickAdd.setOnClickListener { quickAddDay(day) }
            } else {
                holder.binding.llEmptyPunches.visibility = View.GONE
                day.punches.forEach { punch ->
                    val bubbleBinding = ItemPunchBubbleBinding.inflate(LayoutInflater.from(holder.itemView.context), holder.binding.cgPunches, false)
                    val timeSdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                    bubbleBinding.tvPunchTime.text = timeSdf.format(Date(punch.timestamp))
                    
                    bubbleBinding.btnEditPunch.setOnClickListener { showEditPunchDialog(day, punch) }
                    bubbleBinding.btnDeletePunch.setOnClickListener { confirmDeletePunch(day, punch) }
                    
                    holder.binding.cgPunches.addView(bubbleBinding.root)
                }
            }

            holder.binding.btnAddPunch.setOnClickListener {
                showTimePicker { cal ->
                    addManualPunch(day, cal)
                }
            }
        }

        override fun getItemCount() = problematicDays.size
        inner class ViewHolder(val binding: ItemManualPunchDayBinding) : RecyclerView.ViewHolder(binding.root)
    }

    private fun showTimePicker(onSelected: (Calendar) -> Unit) {
        val cal = Calendar.getInstance()
        TimePickerDialog(this, { _, h, m ->
            cal.set(Calendar.HOUR_OF_DAY, h)
            cal.set(Calendar.MINUTE, m)
            onSelected(cal)
        }, 10, 0, false).show()
    }

    private fun quickAddDay(day: ProblemDayInfo) {
        lifecycleScope.launch {
            if (attendanceSafety.isPayrollLocked(day.employee.employeeId.toIntOrNull() ?: 0, day.date)) {
                Toast.makeText(this@AdminManualPunchCorrectionActivity, "Payroll is finalized for this month. Cannot modify punches. 🔒", Toast.LENGTH_LONG).show()
                return@launch
            }
            val startStr = day.employee.shiftStart.ifBlank { "09:00" }
            val endStr = day.employee.shiftEnd.ifBlank { "18:00" }
            
            val startCal = parseTimeOnDate(day.date, startStr)
            val endCal = parseTimeOnDate(day.date, endStr)
            
            val p1 = AttendancePunch(
                punchId = UUID.randomUUID().toString(),
                staffId = day.employee.employeeId,
                date = day.dateStr,
                timestamp = startCal.timeInMillis,
                type = "IN",
                source = "MANUAL_CORRECTION",
                status = "APPROVED"
            )
            val p2 = AttendancePunch(
                punchId = UUID.randomUUID().toString(),
                staffId = day.employee.employeeId,
                date = day.dateStr,
                timestamp = endCal.timeInMillis,
                type = "OUT",
                source = "MANUAL_CORRECTION",
                status = "APPROVED"
            )
            
            sharedViewModel.insertPunch(p1)
            sharedViewModel.insertPunch(p2)
            
            day.punches.add(p1)
            day.punches.add(p2)
            day.punches.sortBy { it.timestamp }
            adapter.notifyDataSetChanged()
            Toast.makeText(this@AdminManualPunchCorrectionActivity, "Full day added (${startStr} - ${endStr}) ✅", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addManualPunch(day: ProblemDayInfo, time: Calendar) {
        val dateCal = Calendar.getInstance().apply { 
            timeInMillis = day.date
            set(Calendar.HOUR_OF_DAY, time[Calendar.HOUR_OF_DAY])
            set(Calendar.MINUTE, time[Calendar.MINUTE])
        }
        
        val newPunch = AttendancePunch(
            punchId = UUID.randomUUID().toString(),
            staffId = day.employee.employeeId,
            date = day.dateStr,
            timestamp = dateCal.timeInMillis,
            type = if (day.punches.size % 2 == 0) "IN" else "OUT",
            source = "MANUAL_CORRECTION",
            status = "APPROVED"
        )
        
        lifecycleScope.launch {
            if (attendanceSafety.isPayrollLocked(day.employee.employeeId.toIntOrNull() ?: 0, day.date)) {
                Toast.makeText(this@AdminManualPunchCorrectionActivity, "Payroll is finalized for this month. Cannot modify punches. 🔒", Toast.LENGTH_LONG).show()
                return@launch
            }
            sharedViewModel.insertPunch(newPunch)
            day.punches.add(newPunch)
            day.punches.sortBy { it.timestamp }
            adapter.notifyDataSetChanged()
            Toast.makeText(this@AdminManualPunchCorrectionActivity, "Punch added ✅", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditPunchDialog(day: ProblemDayInfo, punch: AttendancePunch) {
        val cal = Calendar.getInstance().apply { timeInMillis = punch.timestamp }
        TimePickerDialog(this, { _, h, m ->
            val dateCal = Calendar.getInstance().apply {
                timeInMillis = day.date
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
            }
            
            lifecycleScope.launch {
                if (attendanceSafety.isPayrollLocked(day.employee.employeeId.toIntOrNull() ?: 0, day.date)) {
                    Toast.makeText(this@AdminManualPunchCorrectionActivity, "Payroll is finalized for this month. Cannot modify punches. 🔒", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val updated = punch.copy(timestamp = dateCal.timeInMillis)
                sharedViewModel.insertPunch(updated)
                
                day.punches.remove(punch)
                day.punches.add(updated)
                day.punches.sortBy { it.timestamp }
                adapter.notifyDataSetChanged()
            }
        }, cal[Calendar.HOUR_OF_DAY], cal[Calendar.MINUTE], false).show()
    }

    private fun confirmDeletePunch(day: ProblemDayInfo, punch: AttendancePunch) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Punch")
            .setMessage("Remove this punch record?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    if (attendanceSafety.isPayrollLocked(day.employee.employeeId.toIntOrNull() ?: 0, day.date)) {
                        Toast.makeText(this@AdminManualPunchCorrectionActivity, "Payroll is finalized for this month. Cannot modify punches. 🔒", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    sharedViewModel.deletePunch(punch)
                    day.punches.remove(punch)
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this@AdminManualPunchCorrectionActivity, "Punch deleted 🗑️", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseTimeOnDate(date: Long, timeStr: String): Calendar {
        val cal = Calendar.getInstance().apply { timeInMillis = date }
        try {
            val parts = timeStr.split(":")
            cal.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
            cal.set(Calendar.MINUTE, parts[1].toInt())
        } catch (_: Exception) {
            cal.set(Calendar.HOUR_OF_DAY, 9)
            cal.set(Calendar.MINUTE, 0)
        }
        return cal
    }
}
