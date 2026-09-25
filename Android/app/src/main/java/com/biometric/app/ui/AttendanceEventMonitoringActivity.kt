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
import com.biometric.app.data.dao.LocalAuditLogDao
import com.biometric.app.data.entity.AuditLog
import com.biometric.app.data.entity.LocalAuditLog
import com.biometric.app.databinding.ActivityAttendanceEventMonitoringBinding
import com.biometric.app.databinding.ItemAttendanceEventRowBinding
import com.biometric.app.sync.FirebaseSyncManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    @Inject lateinit var localAuditLogDao: LocalAuditLogDao

    private lateinit var adapter: AttendanceEventAdapter

    private var startDate: Calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
    private var endDate: Calendar = Calendar.getInstance()
    private var selectedEventType: String = ""
    private var quickFilter: String = "ALL" // "ALL", "SUCCESS", "WARNINGS", "FAILED", "FORCED"

    private var allAuditLogs = mutableListOf<AuditLog>()

    private val displayDateFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())
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
            loadInitialData()
            binding.swipeRefresh.isRefreshing = false
        }

        binding.btnRefreshEvents.setOnClickListener {
            binding.progressBar.isVisible = true
            loadInitialData()
            binding.progressBar.isVisible = false
        }

        binding.btnSearchLogs.setOnClickListener {
            filterAndRenderEvents()
        }
    }

    fun loadInitialData() {
        lifecycleScope.launch {
            val localLogs = withContext(Dispatchers.IO) {
                localAuditLogDao.getByAction("AUTH_SESSION")
            }
            if (localLogs.isNotEmpty()) {
                mergeLogs(localLogs.map { it.toDomain() })
            }
            syncWithFirebase()
        }
    }

    private fun observeEventsRealtime() {
        // 1. Observe Room local SQLite flow (SSOT)
        lifecycleScope.launch {
            localAuditLogDao.getByActionFlow("AUTH_SESSION").collectLatest { localList ->
                mergeLogs(localList.map { it.toDomain() })
            }
        }

        // 2. Real-time Firebase listener
        syncWithFirebase()
    }

    private fun syncWithFirebase() {
        val query = sync.getOwnerRef()?.child("audit_logs")?.limitToLast(1000) ?: return
        query.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lifecycleScope.launch(Dispatchers.Default) {
                    val fbLogs = mutableListOf<AuditLog>()
                    val roomEntities = mutableListOf<LocalAuditLog>()

                    for (child in snapshot.children) {
                        val log = child.toAuditLogSafe()
                        if (log.action.equals("AUTH_SESSION", ignoreCase = true)) {
                            fbLogs.add(log)
                            roomEntities.add(log.toLocal())
                        }
                    }

                    // Save to Room for offline persistence
                    if (roomEntities.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            localAuditLogDao.upsertAll(roomEntities)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        mergeLogs(fbLogs)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun mergeLogs(incoming: List<AuditLog>) {
        val map = allAuditLogs.associateBy { it.logId }.toMutableMap()
        incoming.forEach { map[it.logId] = it }
        allAuditLogs = map.values.sortedByDescending { it.timestamp }.toMutableList()
        filterAndRenderEvents()
    }

    private fun filterAndRenderEvents() {
        // Date range boundaries in India Timezone
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

    private fun unwrapDiagnosticDetails(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching {
            val json = JSONObject(raw)
            if (json.has("details")) {
                val inner = json.optString("details")
                if (inner.startsWith("{")) inner else raw
            } else {
                raw
            }
        }.getOrDefault(raw)
    }

    private fun extractEventType(raw: String?): String {
        if (raw.isNullOrBlank()) return "UNKNOWN"
        return runCatching {
            val unwrapped = unwrapDiagnosticDetails(raw)
            val json = JSONObject(unwrapped)
            val eventType = json.optString("EventType", json.optString("eventType", ""))
            if (eventType.isNotBlank()) eventType else "UNKNOWN"
        }.getOrDefault("UNKNOWN")
    }

    private fun formatDiagnosticDetails(details: String?): String {
        if (details.isNullOrBlank()) return "No diagnostic payload available"
        return runCatching {
            val unwrapped = unwrapDiagnosticDetails(details)
            val json = JSONObject(unwrapped)
            json.toString(2)
        }.getOrElse { details }
    }

    private fun DataSnapshot.toAuditLogSafe(): AuditLog {
        val id = child("logId").value?.toString() ?: key.orEmpty()
        val shopId = child("shopId").value?.toString().orEmpty()
        val action = child("action").value?.toString().orEmpty()
        val module = child("module").value?.toString().orEmpty()

        val oldValueRaw = child("oldValue").value
        val oldValue = when (oldValueRaw) {
            is Map<*, *> -> runCatching { Gson().toJson(oldValueRaw) }.getOrNull()
            is String -> oldValueRaw
            else -> oldValueRaw?.toString()
        }

        val newValueRaw = child("newValue").value
        val newValue = when (newValueRaw) {
            is Map<*, *> -> runCatching { Gson().toJson(newValueRaw) }.getOrNull()
            is String -> newValueRaw
            else -> newValueRaw?.toString()
        }

        val userDisplayName = child("userDisplayName").value?.toString().orEmpty()
        val userId = child("userId").value?.toString().orEmpty()
        val actorRole = child("actorRole").value?.toString().orEmpty()
        val ownerUid = child("ownerUid").value?.toString().orEmpty()
        val targetId = child("targetId").value?.toString()
        val rawTs = when (val ts = child("timestamp").value) {
            is Number -> ts.toLong()
            is String -> ts.toLongOrNull() ?: System.currentTimeMillis()
            else -> System.currentTimeMillis()
        }
        val timestamp = if (rawTs in 1..9999999999L) rawTs * 1000L else rawTs

        return AuditLog(
            logId = id,
            shopId = shopId,
            action = action,
            module = module,
            oldValue = oldValue,
            newValue = newValue,
            userDisplayName = userDisplayName,
            userId = userId,
            actorRole = actorRole,
            ownerUid = ownerUid,
            targetId = targetId,
            timestamp = timestamp
        )
    }

    private fun LocalAuditLog.toDomain(): AuditLog {
        val normalizedTs = if (timestamp in 1..9999999999L) timestamp * 1000L else timestamp
        return AuditLog(
            logId = logId,
            shopId = shopId,
            action = action,
            module = module,
            oldValue = oldValue,
            newValue = newValue,
            userDisplayName = userDisplayName,
            userId = userId,
            actorRole = actorRole,
            ownerUid = ownerUid,
            targetId = targetId,
            timestamp = normalizedTs
        )
    }

    private fun AuditLog.toLocal(): LocalAuditLog = LocalAuditLog(
        logId = logId,
        shopId = shopId,
        action = action,
        module = module,
        oldValue = oldValue,
        newValue = newValue,
        userDisplayName = userDisplayName,
        userId = userId,
        actorRole = actorRole,
        ownerUid = ownerUid,
        targetId = targetId,
        timestamp = timestamp,
        syncState = 1
    )

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

                // User Identity: Email and ID matching Web
                val userEmail = if (item.userDisplayName.isNotBlank()) {
                    item.userDisplayName
                } else if (item.userId.isNotBlank()) {
                    item.userId
                } else {
                    "Anonymous User"
                }
                itemBinding.tvUserEmail.text = userEmail

                val entityIdText = if (!item.targetId.isNullOrBlank()) {
                    item.targetId
                } else if (item.userId.isNotBlank()) {
                    item.userId
                } else {
                    "Session"
                }
                itemBinding.tvEntityId.text = entityIdText

                // Formatted diagnostic JSON
                val diagnosticJson = unwrapDiagnosticDetails(item.newValue)
                itemBinding.tvDiagnosticText.text = formatDiagnosticDetails(diagnosticJson)
            }
        }
    }
}
