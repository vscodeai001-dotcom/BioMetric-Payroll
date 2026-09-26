package com.biometric.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.AdminShiftDto
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.Employee
import com.biometric.app.databinding.ActivityShiftManagerBinding
import com.biometric.app.databinding.DialogAddShiftBinding
import com.biometric.app.databinding.ItemShiftCardBinding
import com.biometric.app.sync.FirebaseSyncManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ShiftManagerActivity : MotionBaseActivity() {

    @Inject lateinit var firebaseSync: FirebaseSyncManager
    @Inject lateinit var repository: MainRepository

    private lateinit var binding: ActivityShiftManagerBinding
    private lateinit var adapter: ShiftAdapter

    private val allShiftRows = mutableListOf<AdminShiftDto>()
    private val allEmployees = mutableListOf<Employee>()

    private var filterEmployeeId: Int? = null
    private var filterStartDate = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var filterEndDate = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
    }

    private var shiftsJob: Job? = null

    private val displayDateFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayOfWeekFormat = SimpleDateFormat("EEE", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShiftManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets(binding.clShiftManagerRoot, binding.appBar)

        setupToolbar()
        setupDatePickers()
        setupRecyclerView()
        setupListeners()

        observeEmployees()
        observeShifts()
    }

    override fun onDestroy() {
        shiftsJob?.cancel()
        super.onDestroy()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupDatePickers() {
        binding.tvFilterStartDate.text = displayDateFormat.format(filterStartDate.time)
        binding.tvFilterEndDate.text = displayDateFormat.format(filterEndDate.time)

        binding.cardFilterStartDate.setOnClickListener {
            showDatePicker(filterStartDate) { picked ->
                filterStartDate = picked
                binding.tvFilterStartDate.text = displayDateFormat.format(filterStartDate.time)
                filterAndRender()
            }
        }

        binding.cardFilterEndDate.setOnClickListener {
            showDatePicker(filterEndDate) { picked ->
                filterEndDate = picked
                binding.tvFilterEndDate.text = displayDateFormat.format(filterEndDate.time)
                filterAndRender()
            }
        }
    }

    private fun showDatePicker(base: Calendar, onDateSelected: (Calendar) -> Unit) {
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val result = Calendar.getInstance().apply { set(year, month, dayOfMonth) }
                onDateSelected(result)
            },
            base.get(Calendar.YEAR),
            base.get(Calendar.MONTH),
            base.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTimePicker(initialTime: String, onTimeSelected: (String) -> Unit) {
        val parts = initialTime.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        TimePickerDialog(
            this,
            { _, h, m ->
                onTimeSelected(String.format(Locale.US, "%02d:%02d", h, m))
            },
            hour,
            minute,
            true
        ).show()
    }

    private fun setupRecyclerView() {
        adapter = ShiftAdapter(
            onDelete = { row -> confirmDeleteShift(row) }
        )
        binding.rvShifts.layoutManager = LinearLayoutManager(this)
        binding.rvShifts.adapter = adapter
    }

    private fun setupListeners() {
        binding.swipeRefresh.setOnRefreshListener {
            filterAndRender()
            binding.swipeRefresh.isRefreshing = false
        }

        binding.fabAdd.setOnClickListener {
            showAddShiftDialog()
        }

        binding.btnGenerate.setOnClickListener {
            confirmGenerateShifts()
        }
    }

    private fun observeEmployees() {
        lifecycleScope.launch {
            repository.allEmployeesFlow.collectLatest { list ->
                allEmployees.clear()
                allEmployees.addAll(list.filter { it.isActive }.sortedBy { it.name })

                val items = mutableListOf("All Employees")
                items += allEmployees.map { "${it.name} (#${it.employeeId})" }
                binding.spFilterEmployee.adapter = ArrayAdapter(
                    this@ShiftManagerActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    items
                )
            }
        }

        binding.spFilterEmployee.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                filterEmployeeId = null
                filterAndRender()
            }
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filterEmployeeId = allEmployees.getOrNull(position - 1)?.employeeId?.toIntOrNull()
                filterAndRender()
            }
        }
    }

    private fun observeShifts() {
        shiftsJob?.cancel()
        shiftsJob = lifecycleScope.launch {
            firebaseSync.observeShiftSchedules().collectLatest { remoteRows ->
                val names = allEmployees.associate { (it.employeeId.toIntOrNull() ?: 0) to it.name }

                val mapped = remoteRows.map {
                    AdminShiftDto(
                        id = it.scheduleId,
                        employeeId = it.employeeId,
                        employeeName = names[it.employeeId] ?: "Employee #${it.employeeId}",
                        shiftDate = it.shiftDate,
                        startTime = it.startTime,
                        endTime = it.endTime,
                        isRecurringPattern = it.isRecurringPattern,
                        patternDurationDays = it.patternDurationDays,
                        dayOfWeek = it.appliesToDayOfWeek
                    )
                }

                allShiftRows.clear()
                allShiftRows.addAll(mapped)
                filterAndRender()
            }
        }
    }

    private fun filterAndRender() {
        val startIso = isoDateFormat.format(filterStartDate.time)
        val endIso = isoDateFormat.format(filterEndDate.time)

        val filtered = allShiftRows.filter { s ->
            val matchesEmployee = filterEmployeeId == null || s.employeeId == filterEmployeeId
            // Recurring patterns always show up as templates or concrete schedules matching date
            val matchesDate = s.isRecurringPattern || (s.shiftDate in startIso..endIso)
            matchesEmployee && matchesDate
        }.sortedWith(compareByDescending<AdminShiftDto> { it.shiftDate }.thenBy { it.employeeName })

        // KPI Counters
        val totalCount = filtered.size
        val recurringCount = filtered.count { it.isRecurringPattern }
        val singleDayCount = filtered.count { !it.isRecurringPattern }
        val staffCoveredCount = filtered.map { it.employeeId }.distinct().count()

        binding.tvTotalCount.text = totalCount.toString()
        binding.tvRecurringCount.text = recurringCount.toString()
        binding.tvSingleDayCount.text = singleDayCount.toString()
        binding.tvStaffCoveredCount.text = staffCoveredCount.toString()

        adapter.submitList(filtered)
        binding.llEmptyState.isVisible = filtered.isEmpty()
        binding.rvShifts.isVisible = filtered.isNotEmpty()
    }

    private fun confirmGenerateShifts() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Generate Shifts from Patterns 📅")
            .setMessage("Generate daily shifts based on active recurring patterns for the next 30 days? Existing schedules will be preserved.")
            .setPositiveButton("Generate") { _, _ ->
                lifecycleScope.launch {
                    val count = firebaseSync.generateShiftSchedulesFromPatterns()
                    toast(
                        if (count > 0) "$count shifts generated for the next 30 days 📅✅"
                        else "No new shifts were generated. Check recurring patterns."
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddShiftDialog() {
        val d = DialogAddShiftBinding.inflate(layoutInflater)

        // Setup Employee Dropdown with Employee Names
        val employeeDisplayList = allEmployees.map { "${it.name} (#${it.employeeId})" }
        val empAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            employeeDisplayList
        )
        d.actvEmployee.setAdapter(empAdapter)

        // Pre-select employee: either currently filtered employee or first in list
        val defaultEmployee = if (filterEmployeeId != null) {
            allEmployees.find { it.employeeId.toIntOrNull() == filterEmployeeId } ?: allEmployees.firstOrNull()
        } else {
            allEmployees.firstOrNull()
        }
        if (defaultEmployee != null) {
            d.actvEmployee.setText("${defaultEmployee.name} (#${defaultEmployee.employeeId})", false)
        }

        d.actvEmployee.setOnClickListener {
            d.actvEmployee.showDropDown()
        }

        d.etDate.setText(isoDateFormat.format(Date()))

        d.etDate.isFocusable = false
        d.etDate.setOnClickListener {
            val c = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, day ->
                d.etDate.setText(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, day))
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }

        d.etStart.isFocusable = false
        d.etStart.setOnClickListener {
            showTimePicker(d.etStart.text.toString()) { time -> d.etStart.setText(time) }
        }

        d.etEnd.isFocusable = false
        d.etEnd.setOnClickListener {
            showTimePicker(d.etEnd.text.toString()) { time -> d.etEnd.setText(time) }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Schedule Employee Shift 🕒📅")
            .setMessage("Assign shift timing. Recurring patterns repeat across upcoming dates.")
            .setView(d.root)
            .setPositiveButton("Save Shift ✅") { _, _ ->
                val empText = d.actvEmployee.text.toString().trim()
                val selectedEmployee = allEmployees.find { "${it.name} (#${it.employeeId})" == empText }
                    ?: allEmployees.find { it.name.equals(empText, ignoreCase = true) }
                    ?: allEmployees.find { it.employeeId == empText }
                val employeeId = selectedEmployee?.employeeId?.toIntOrNull()
                    ?: empText.substringAfter("(#").substringBefore(")").toIntOrNull()
                    ?: empText.toIntOrNull()
                    ?: 0

                val date = d.etDate.text.toString().trim()
                val start = d.etStart.text.toString().trim()
                val end = d.etEnd.text.toString().trim()
                val recurring = d.cbRecurring.isChecked
                val duration = d.etDuration.text.toString().toIntOrNull() ?: 7

                if (employeeId <= 0 || date.isBlank() || start.isBlank() || end.isBlank()) {
                    toast("Please select an employee, date, and valid times ⚠️")
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    val id = (allShiftRows.maxOfOrNull { it.id } ?: 0) + 1
                    val day = runCatching { java.time.LocalDate.parse(date).dayOfWeek.value % 7 }.getOrDefault(0)

                    val record = FirebaseSyncManager.ShiftScheduleRecord(
                        scheduleId = id,
                        employeeId = employeeId,
                        shiftDate = if (recurring) {
                            runCatching {
                                java.time.LocalDate.parse(date)
                                    .minusDays(java.time.LocalDate.parse(date).dayOfWeek.value.toLong() % 7)
                                    .toString()
                            }.getOrDefault(date)
                        } else date,
                        startTime = if (start.length == 5) "$start:00" else start,
                        endTime = if (end.length == 5) "$end:00" else end,
                        isRecurringPattern = recurring,
                        patternDurationDays = duration,
                        appliesToDayOfWeek = day
                    )

                    if (firebaseSync.pushShiftSchedule(record)) {
                        toast("Shift scheduled successfully ✅")
                    } else {
                        toast("Unable to save shift to Firebase ⚠️")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteShift(row: AdminShiftDto) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Shift 🗑️")
            .setMessage("Delete ${row.employeeName}'s shift on ${row.shiftDate} (${row.startTime} - ${row.endTime})?\nThis action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    if (firebaseSync.deleteShiftSchedule(row.id)) {
                        toast("Shift deleted 🗑️")
                    } else {
                        toast("Unable to delete shift from Firebase ⚠️")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // RECYCLERVIEW ADAPTER
    // ============================================================
    private inner class ShiftAdapter(
        private val onDelete: (AdminShiftDto) -> Unit
    ) : RecyclerView.Adapter<ShiftAdapter.ShiftViewHolder>() {

        private val items = mutableListOf<AdminShiftDto>()

        fun submitList(newItems: List<AdminShiftDto>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShiftViewHolder {
            val itemBinding = ItemShiftCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ShiftViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ShiftViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ShiftViewHolder(private val itemBinding: ItemShiftCardBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(item: AdminShiftDto) {
                itemBinding.tvName.text = item.employeeName
                itemBinding.tvEmployeeIdSubtitle.text = "Staff #${item.employeeId}"

                // Pattern Badge
                if (item.isRecurringPattern) {
                    itemBinding.tvPatternBadge.text = "🔄 Recurring (${item.patternDurationDays}d)"
                    itemBinding.tvPatternBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EDE7F6"))
                    itemBinding.tvPatternBadge.setTextColor(Color.parseColor("#5E35B1"))
                } else {
                    itemBinding.tvPatternBadge.text = "📌 Single Shift"
                    itemBinding.tvPatternBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                    itemBinding.tvPatternBadge.setTextColor(Color.parseColor("#2E7D32"))
                }

                // Date & Day
                val parsedDate = runCatching { isoDateFormat.parse(item.shiftDate) }.getOrNull()
                val dateWithDay = if (parsedDate != null) {
                    "${displayDateFormat.format(parsedDate)} (${dayOfWeekFormat.format(parsedDate)})"
                } else {
                    item.shiftDate
                }
                itemBinding.tvShiftDate.text = "🗓️ $dateWithDay"

                // Time Duration calculation
                val cleanStart = item.startTime.take(5)
                val cleanEnd = item.endTime.take(5)
                itemBinding.tvShiftTiming.text = "⏰ $cleanStart - $cleanEnd"

                itemBinding.tvDetails.text = if (item.isRecurringPattern) {
                    "Rotational pattern • Repeats every ${item.patternDurationDays} days"
                } else {
                    "Fixed shift assignment • Overrides default employee schedule"
                }

                itemBinding.btnDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}
