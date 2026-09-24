package com.biometric.app.ui

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.entity.AuditLog
import com.biometric.app.databinding.ActivityAttendanceEventMonitoringBinding
import com.biometric.app.databinding.ItemAttendanceEventRowBinding
import com.biometric.app.sync.FirebaseSyncManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

@AndroidEntryPoint
class AttendanceEventMonitoringActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityAttendanceEventMonitoringBinding

    @Inject lateinit var sync: FirebaseSyncManager

    private lateinit var adapter: AttendanceEventAdapter

    private var startDate: Calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
    private var endDate: Calendar = Calendar.getInstance()
    private var selectedEventType: String = ""
    private var quickFilter: String = "ALL" // "ALL", "SUCCESS", "WARNINGS", "FAILED", "FORCED"

    private var allAuditLogs = listOf<AuditLog>()

    private val displayDateFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fullTimestampFormat = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }

    private val eventTypeOptions = listOf(
        "All Security Events",
        "LOGIN_ATTEMPT",
        "LOGIN_SUCCESS",
        "LOGIN_FAILED",
        "SECOND_DEVICE_ATTEMPT",
        "FORCE_LOGOUT_REQUESTED",
        "FORCED_SESSION_LOGOUT",
        "LOGOUT_REQUESTED",
        "DEVICE_LOCK_RELEASED",
        "LOGOUT_COMPLETED"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceEventMonitoringBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets(binding.rootLayout)

        setupToolbar()
        setupDatePickers()
        setupEventTypeSpinner()
        setupQuickFilterChips()
        setupRecyclerView()
        setupListeners()

        observeEventsRealtime()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupDatePickers() {
        binding.tvStartDate.text = displayDateFormat.format(startDate.time)
        binding.tvEndDate.text = displayDateFormat.format(endDate.time)

        binding.cardStartDate.setOnClickListener {
            showDatePicker(startDate) { picked ->
                startDate = picked
                binding.tvStartDate.text = displayDateFormat.format(startDate.time)
                filterAndRenderEvents()
            }
        }

        binding.cardEndDate.setOnClickListener {
            showDatePicker(endDate) { picked ->
                endDate = picked
                binding.tvEndDate.text = displayDateFormat.format(endDate.time)
                filterAndRenderEvents()
            }
        }
    }

    private fun showDatePicker(initialCalendar: Calendar, onDateSet: (Calendar) -> Unit) {
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val result = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                onDateSet(result)
            },
            initialCalendar.get(Calendar.YEAR),
            initialCalendar.get(Calendar.MONTH),
            initialCalendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun setupEventTypeSpinner() {
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            eventTypeOptions
        )
        binding.spEventType.adapter = spinnerAdapter
        binding.spEventType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedEventType = if (position == 0) "" else eventTypeOptions[position]
                filterAndRenderEvents()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupQuickFilterChips() {
        binding.chipGroupEventFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            quickFilter = when (checkedIds.firstOrNull()) {
                R.id.chipSuccess -> "SUCCESS"
                R.id.chipWarnings -> "WARNINGS"
                R.id.chipFailed -> "FAILED"
                R.id.chipForced -> "FORCED"
                else -> "ALL"
            }
            filterAndRenderEvents()
        }
    }

    private fun setupRecyclerView() {
        adapter = AttendanceEventAdapter()
        binding.rvEvents.layoutManager = LinearLayoutManager(this)
        binding.rvEvents.adapter = adapter
    }

    private fun setupListeners() {
        binding.swipeRefresh.setOnRefreshListener {
            filterAndRenderEvents()
            binding.swipeRefresh.isRefreshing = false
        }

        binding.btnRefreshEvents.setOnClickListener {
            binding.progressBar.isVisible = true
            filterAndRenderEvents()
            binding.progressBar.isVisible = false
        }

        binding.btnSearchLogs.setOnClickListener {
            filterAndRenderEvents()
        }
    }

    private fun observeEventsRealtime() {
        val query = sync.getOwnerRef()?.child("audit_logs")?.limitToLast(500)
        if (query != null) {
            lifecycleScope.launch {
                sync.getQueryFlow<AuditLog>(query).collectLatest { logs ->
                    allAuditLogs = logs.filter { it.action.equals("AUTH_SESSION", ignoreCase = true) }
                    filterAndRenderEvents()
                }
            }
        }
    }

    private fun filterAndRenderEvents() {
        // Date range boundaries
        val startOfDay = Calendar.getInstance().apply {
            time = startDate.time
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val endOfDay = Calendar.getInstance().apply {
            time = endDate.time
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val dateFiltered = allAuditLogs.filter { log ->
            log.timestamp in startOfDay..endOfDay
        }

        // Calculate KPI counters across all events in the date range
        var successCount = 0
        var warningCount = 0
        var failedCount = 0

        dateFiltered.forEach { log ->
            val eventType = extractEventType(log.newValue)
            when (eventType) {
                "LOGIN_SUCCESS", "LOGOUT_COMPLETED", "DEVICE_LOCK_RELEASED" -> successCount++
                "SECOND_DEVICE_ATTEMPT", "FORCE_LOGOUT_REQUESTED", "LOGOUT_REQUESTED" -> warningCount++
                "FORCED_SESSION_LOGOUT", "LOGIN_FAILED" -> failedCount++
            }
        }

        binding.tvTotalEventsCount.text = dateFiltered.size.toString()
        binding.tvSuccessCount.text = successCount.toString()
        binding.tvWarningCount.text = warningCount.toString()
        binding.tvFailedCount.text = failedCount.toString()

        // Apply Event Type Spinner filter
        var result = if (selectedEventType.isNotBlank()) {
            dateFiltered.filter { log ->
                val type = extractEventType(log.newValue)
                type.equals(selectedEventType, ignoreCase = true)
            }
        } else {
            dateFiltered
        }

        // Apply Quick Filter Chip
        result = when (quickFilter) {
            "SUCCESS" -> result.filter {
                val t = extractEventType(it.newValue)
                t == "LOGIN_SUCCESS" || t == "LOGOUT_COMPLETED" || t == "DEVICE_LOCK_RELEASED"
            }
            "WARNINGS" -> result.filter {
                val t = extractEventType(it.newValue)
                t == "SECOND_DEVICE_ATTEMPT" || t == "FORCE_LOGOUT_REQUESTED" || t == "LOGOUT_REQUESTED"
            }
            "FAILED" -> result.filter {
                val t = extractEventType(it.newValue)
                t == "LOGIN_FAILED"
            }
            "FORCED" -> result.filter {
                val t = extractEventType(it.newValue)
                t == "FORCED_SESSION_LOGOUT"
            }
            else -> result
        }

        // Sort descending by timestamp (newest first)
        val sortedList = result.sortedByDescending { it.timestamp }
        adapter.submitList(sortedList)

        binding.llEmptyState.isVisible = sortedList.isEmpty()
        binding.rvEvents.isVisible = sortedList.isNotEmpty()
    }

    private fun extractEventType(details: String?): String {
        if (details.isNullOrBlank()) return "UNKNOWN"
        return runCatching {
            JSONObject(details).optString("EventType", "UNKNOWN")
        }.getOrDefault("UNKNOWN")
    }

    private fun formatDiagnosticDetails(details: String?): String {
        if (details.isNullOrBlank()) return "No diagnostic payload available"
        return runCatching {
            val json = JSONObject(details)
            json.toString(2)
        }.getOrElse { details }
    }

    // ============================================================
    // RECYCLERVIEW ADAPTER
    // ============================================================
    private inner class AttendanceEventAdapter : RecyclerView.Adapter<AttendanceEventAdapter.EventViewHolder>() {

        private val items = mutableListOf<AuditLog>()

        fun submitList(newItems: List<AuditLog>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
            val itemBinding = ItemAttendanceEventRowBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return EventViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class EventViewHolder(private val itemBinding: ItemAttendanceEventRowBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(item: AuditLog) {
                val eventType = extractEventType(item.newValue)

                itemBinding.tvEventTypeBadge.text = eventType
                itemBinding.tvTimestamp.text = fullTimestampFormat.format(Date(item.timestamp))

                // Configure Badge styling based on Web event definitions
                when (eventType) {
                    "LOGIN_SUCCESS", "LOGOUT_COMPLETED", "DEVICE_LOCK_RELEASED" -> {
                        itemBinding.tvEventTypeBadge.setBackgroundResource(R.drawable.bg_chip_rounded)
                        itemBinding.tvEventTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                        itemBinding.tvEventTypeBadge.setTextColor(Color.parseColor("#2E7D32"))
                    }
                    "SECOND_DEVICE_ATTEMPT", "FORCE_LOGOUT_REQUESTED", "LOGOUT_REQUESTED" -> {
                        itemBinding.tvEventTypeBadge.setBackgroundResource(R.drawable.bg_chip_rounded)
                        itemBinding.tvEventTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                        itemBinding.tvEventTypeBadge.setTextColor(Color.parseColor("#EF6C00"))
                    }
                    "FORCED_SESSION_LOGOUT", "LOGIN_FAILED" -> {
                        itemBinding.tvEventTypeBadge.setBackgroundResource(R.drawable.bg_chip_rounded)
                        itemBinding.tvEventTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFEBEE"))
                        itemBinding.tvEventTypeBadge.setTextColor(Color.parseColor("#C62828"))
                    }
                    else -> {
                        itemBinding.tvEventTypeBadge.setBackgroundResource(R.drawable.bg_chip_rounded)
                        itemBinding.tvEventTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#ECEFF1"))
                        itemBinding.tvEventTypeBadge.setTextColor(Color.parseColor("#455A64"))
                    }
                }

                // User Identity
                val userEmail = if (item.userDisplayName.isNotBlank()) {
                    item.userDisplayName
                } else if (item.userId.isNotBlank()) {
                    item.userId
                } else {
                    "Anonymous User"
                }
                itemBinding.tvUserEmail.text = userEmail

                val entityIdText = if (!item.targetId.isNullOrBlank()) {
                    "Entity: ${item.targetId} • Role: ${item.actorRole.ifBlank { "User" }}"
                } else {
                    "Role: ${item.actorRole.ifBlank { "System" }}"
                }
                itemBinding.tvEntityId.text = entityIdText

                // Formatted diagnostic JSON
                itemBinding.tvDiagnosticText.text = formatDiagnosticDetails(item.newValue)
            }
        }
    }
}
