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

    private var auditQuery: com.google.firebase.database.Query? = null
    private var auditEventListener: ValueEventListener? = null

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
        auditEventListener?.let { auditQuery?.removeEventListener(it) }
        val query = sync.getOwnerRef()?.child("audit_logs")?.limitToLast(100) ?: return
        auditQuery = query
        val listener = object : ValueEventListener {
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
        }
        auditEventListener = listener
        query.addValueEventListener(listener)
    }

    override fun onDestroy() {
        auditEventListener?.let { auditQuery?.removeEventListener(it) }
        auditEventListener = null
        auditQuery = null
        super.onDestroy()
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
    data class SecurityEventModel(
        val icon: String,
        val title: String,
        val relativeTime: String,
        val eventType: String,
        val platform: String,
        val why: String,
        val purpose: String,
        val how: String,
        val activePlatform: String,
        val activeDeviceName: String,
        val activeDeviceId: String,
        val activeIp: String,
        val activeStatus: String,
        val hasDisplaced: Boolean,
        val displacedPlatform: String = "",
        val displacedDeviceName: String = "",
        val displacedDeviceId: String = "",
        val displacedLastSeen: String = "",
        val displacedGps: String = "",
        val displacedDistance: String = "",
        val displacedWithinGeofence: Boolean? = null,
        val displacedStatus: String = ""
    )

    private fun getRelativeTimeSpan(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 45 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> displayDateFormat.format(Date(timestamp))
        }
    }

    private fun parseSecurityEvent(rawJson: String?, timestamp: Long): SecurityEventModel {
        val relative = getRelativeTimeSpan(timestamp)
        if (rawJson.isNullOrBlank()) {
            return SecurityEventModel(
                icon = "🛡️",
                title = "Security Event",
                relativeTime = relative,
                eventType = "AUTH_SESSION",
                platform = "System",
                why = "Authentication event logged without diagnostic payload.",
                purpose = "Employee session verification.",
                how = "Processed through identity verification.",
                activePlatform = "System",
                activeDeviceName = "Application Client",
                activeDeviceId = "",
                activeIp = "",
                activeStatus = "Processed",
                hasDisplaced = false
            )
        }

        return try {
            val unwrapped = unwrapDiagnosticDetails(rawJson)
            val json = JSONObject(unwrapped)

            val eventType = json.optString("EventType", json.optString("eventType", "AUTH_SESSION"))
            val platform = json.optString("Platform", json.optString("platform", "Android"))
            val deviceId = json.optString("DeviceId", json.optString("deviceId", ""))
            val reason = json.optString("Reason", json.optString("reason", ""))
            val relatedDeviceId = json.optString("RelatedDeviceId", "")

            val contextObj = json.optJSONObject("Context")
            val forceLogoutExisting = contextObj?.optBoolean("ForceLogoutExisting")
                ?: json.optBoolean("forceLogoutExisting", false)

            // Active Device info
            var devName = contextObj?.optString("DeviceName")?.takeIf { it.isNotBlank() }
                ?: json.optString("DeviceName", json.optString("deviceName", ""))
            if (devName.isBlank()) {
                devName = if (platform.equals("Web", true)) "Web Browser" else "Android Device"
            }

            var ip = contextObj?.optString("IpAddress")?.takeIf { it.isNotBlank() }
                ?: json.optString("IpAddress", json.optString("ipAddress", ""))
            if (ip == "Unavailable") ip = ""

            // Displaced Device
            var hasDisplaced = false
            var dPlatform = ""
            var dDeviceName = ""
            var dDeviceId = ""
            var dLastSeen = ""
            var dGps = ""
            var dDistance = ""
            var dWithinGeofence: Boolean? = null
            var dStatus = ""

            val dispObj = contextObj?.optJSONObject("DisplacedDevice") ?: json.optJSONObject("DisplacedDevice")
            if (dispObj != null) {
                hasDisplaced = true
                dPlatform = dispObj.optString("Platform", "Device")
                dDeviceName = dispObj.optString("DeviceName", "")
                dDeviceId = dispObj.optString("DeviceId", "")
                dLastSeen = dispObj.optString("LastSeen", "")
                val lat = dispObj.optDouble("LastLatitude", 0.0)
                val lng = dispObj.optDouble("LastLongitude", 0.0)
                if (lat != 0.0 || lng != 0.0) {
                    dGps = String.format(Locale.US, "Last GPS: Lat %.4f, Lng %.4f", lat, lng)
                }
                val dist = dispObj.optDouble("LastDistanceMeters", -1.0)
                if (dist >= 0) {
                    dDistance = String.format(Locale.US, "Distance: %.0fm", dist)
                }
                if (dispObj.has("WithinAllowedRadius")) {
                    dWithinGeofence = dispObj.optBoolean("WithinAllowedRadius")
                }
                dStatus = "Session Disconnected & Tracking Stopped"
            } else if (json.has("displacedPlatform")) {
                hasDisplaced = true
                dPlatform = json.optString("displacedPlatform", "Device")
                dDeviceName = json.optString("displacedDeviceName", "Previous Device")
                dDeviceId = json.optString("displacedDeviceId", "")
                dStatus = "Session Terminated by Remote Login"
            } else if (relatedDeviceId.isNotBlank()) {
                hasDisplaced = true
                dPlatform = if (relatedDeviceId.startsWith("WEB_BROWSER_", true)) "Web" else "Android"
                dDeviceName = if (relatedDeviceId.startsWith("WEB_BROWSER_", true)) "Web Browser" else "Mobile Device"
                dDeviceId = relatedDeviceId
                dStatus = "Session Replaced"
            } else if (forceLogoutExisting || reason.equals("NEW_DEVICE_AFTER_FORCE_REPLACE", true)) {
                hasDisplaced = true
                dPlatform = if (platform.equals("Web", true)) "Android" else "Web"
                dDeviceName = if (platform.equals("Web", true)) "Previous Mobile Device" else "Previous Web Browser"
                dDeviceId = "Previous Session"
                dStatus = "Session Disconnected & Tracking Stopped"
            }

            var icon = "🛡️"
            var title = "Security Event"
            var why = ""
            var purpose = ""
            var how = ""
            var activeStatus = "Active"

            when (eventType.uppercase(Locale.US)) {
                "LOGIN_SUCCESS" -> {
                    if (forceLogoutExisting || reason.equals("NEW_DEVICE_AFTER_FORCE_REPLACE", true) || hasDisplaced) {
                        icon = "🔄"
                        title = "Device Switched & Session Activated"
                        activeStatus = "✓ Granted Authoritative Session Lock"
                        why = "Employee signed into $platform and confirmed terminating their previous active session."
                        purpose = "Enforce strict single active session rule across Web & Android to prevent dual-device attendance."
                        how = "Updated Firebase RTDB session lock at employee_sessions. Notified old device observer to stop GPS tracking and logout."
                    } else {
                        icon = "🟢"
                        title = "Login Successful"
                        activeStatus = "✓ Granted Active Session Lock"
                        why = "Employee entered valid credentials and logged into $platform with no conflicting session."
                        purpose = "Authenticate employee identity and establish authorized attendance session."
                        how = "Verified credentials, registered active session in Firebase Realtime Database, and began attendance presence."
                    }
                }
                "SECOND_DEVICE_ATTEMPT", "LOGIN_BLOCKED_EXISTING_SESSION" -> {
                    icon = "⛔"
                    title = "Dual Login Blocked (Active Session on Another Device)"
                    activeStatus = "Blocked Pending Confirmation"
                    if (hasDisplaced) dStatus = "Holds Active Session Lock"
                    why = "Employee entered credentials on $platform, but their account is already actively logged in on another device."
                    purpose = "Strict Single-Device Policy: Only one active device allowed per employee across all platforms."
                    how = "Authentication held in pending state without issuing login token. Displayed confirmation prompt."
                }
                "FORCED_SESSION_LOGOUT", "FORCE_LOGOUT_REQUESTED" -> {
                    icon = "⚡"
                    title = "Previous Device Forcefully Displaced"
                    activeStatus = "Replaced Previous Session"
                    if (hasDisplaced) dStatus = "✕ Forcefully Disconnected & Tracking Stopped"
                    why = "A new login was confirmed on another device, triggering immediate displacement of the previous session."
                    purpose = "Single Source of Truth: Ensure all attendance tracking points originate from only one active device."
                    how = "Terminated active GPS sessions in database, revoked session lock in Firebase RTDB, and notified client."
                }
                "LOGOUT_COMPLETED", "MANUAL_LOGOUT_COMPLETED" -> {
                    icon = "👋"
                    title = "Normal User Sign-Out"
                    activeStatus = "Session Ended & Lock Released"
                    why = "Employee logged out to safely conclude their work session on $platform."
                    purpose = "Clean session termination and cessation of background location tracking."
                    how = "Ended GPS tracking session, deleted session presence from Firebase, and cleared authentication tokens."
                }
                "SESSION_DISCONNECTED_BY_SERVER" -> {
                    icon = "🛑"
                    title = "Remote Disconnection (Single Session Rule)"
                    activeStatus = "Terminated Remotely"
                    why = "This device was displaced because the employee logged in from another device/browser."
                    purpose = "Prevent ghost location updates and eliminate orphaned background tracking services."
                    how = "Realtime observer detected ownership change, stopped TrackingService, dismissed notifications, and logged out."
                }
                "LOGIN_ATTEMPT" -> {
                    icon = "🔑"
                    title = "Login Password Verified"
                    activeStatus = "Credentials Validated"
                    why = "Employee submitted credentials to sign in on $platform."
                    purpose = "Verify user identity before evaluating single-session constraints."
                    how = "Validated user identity against ASP.NET Identity authentication store."
                }
                "LOGIN_FAILED", "LOGIN_PASSWORD_FAILED" -> {
                    icon = "❌"
                    title = "Authentication Failed"
                    activeStatus = "Access Denied"
                    why = "Invalid credentials were provided during sign-in attempt."
                    purpose = "Security protection against unauthorized access."
                    how = "Rejected login attempt and logged audit failure."
                }
                else -> {
                    title = eventType.replace("_", " ")
                    why = "System processed a $eventType event for $platform."
                    purpose = "System audit trail."
                    how = "Recorded into database audit logs."
                }
            }

            SecurityEventModel(
                icon = icon,
                title = title,
                relativeTime = relative,
                eventType = eventType,
                platform = platform,
                why = why,
                purpose = purpose,
                how = how,
                activePlatform = platform,
                activeDeviceName = devName,
                activeDeviceId = if (deviceId.isNotBlank()) "ID: $deviceId" else "",
                activeIp = if (ip.isNotBlank()) "IP: $ip" else "",
                activeStatus = activeStatus,
                hasDisplaced = hasDisplaced,
                displacedPlatform = dPlatform,
                displacedDeviceName = dDeviceName,
                displacedDeviceId = if (dDeviceId.isNotBlank()) "ID: $dDeviceId" else "",
                displacedLastSeen = if (dLastSeen.isNotBlank()) "Last Active: $dLastSeen" else "",
                displacedGps = dGps,
                displacedDistance = dDistance,
                displacedWithinGeofence = dWithinGeofence,
                displacedStatus = dStatus
            )
        } catch (e: Exception) {
            SecurityEventModel(
                icon = "🛡️",
                title = "Security Event",
                relativeTime = relative,
                eventType = "AUTH_SESSION",
                platform = "System",
                why = "Error parsing telemetry details: ${e.message}",
                purpose = "Security audit trail.",
                how = "Recorded into database.",
                activePlatform = "System",
                activeDeviceName = "Client",
                activeDeviceId = "",
                activeIp = "",
                activeStatus = "Logged",
                hasDisplaced = false
            )
        }
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
                val eventModel = parseSecurityEvent(item.newValue, item.timestamp)

                itemBinding.tvEventIcon.text = eventModel.icon
                itemBinding.tvEventTitle.text = eventModel.title
                itemBinding.tvRelativeTime.text = "⏱️ ${eventModel.relativeTime}"

                itemBinding.tvEventTypeBadge.text = eventModel.eventType
                itemBinding.tvPlatformBadge.text = if (eventModel.platform.equals("Web", true)) "🌐 Web Browser" else "📱 Android Mobile"
                itemBinding.tvTimestamp.text = fullTimestampFormat.format(Date(item.timestamp))

                // Configure Badge styling based on Web event definitions
                when (eventModel.eventType) {
                    "LOGIN_SUCCESS", "LOGOUT_COMPLETED", "DEVICE_LOCK_RELEASED" -> {
                        itemBinding.tvEventTypeBadge.setBackgroundResource(R.drawable.bg_chip_rounded)
                        itemBinding.tvEventTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                        itemBinding.tvEventTypeBadge.setTextColor(Color.parseColor("#2E7D32"))
                    }
                    "SECOND_DEVICE_ATTEMPT", "FORCE_LOGOUT_REQUESTED", "LOGOUT_REQUESTED", "LOGIN_BLOCKED_EXISTING_SESSION" -> {
                        itemBinding.tvEventTypeBadge.setBackgroundResource(R.drawable.bg_chip_rounded)
                        itemBinding.tvEventTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                        itemBinding.tvEventTypeBadge.setTextColor(Color.parseColor("#EF6C00"))
                    }
                    "FORCED_SESSION_LOGOUT", "LOGIN_FAILED", "LOGIN_PASSWORD_FAILED", "SESSION_DISCONNECTED_BY_SERVER" -> {
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

                // 3 Core Pillars: WHY, PURPOSE, HOW
                itemBinding.tvEventWhy.text = eventModel.why
                itemBinding.tvEventPurpose.text = eventModel.purpose
                itemBinding.tvEventHow.text = eventModel.how

                // Active / Incoming Device Card
                itemBinding.tvActivePlatform.text = eventModel.activePlatform
                itemBinding.tvActiveDeviceName.text = eventModel.activeDeviceName
                itemBinding.tvActiveDeviceId.text = eventModel.activeDeviceId
                itemBinding.tvActiveDeviceId.isVisible = eventModel.activeDeviceId.isNotBlank()
                itemBinding.tvActiveIp.text = eventModel.activeIp
                itemBinding.tvActiveIp.isVisible = eventModel.activeIp.isNotBlank()
                itemBinding.tvActiveStatus.text = eventModel.activeStatus

                // Displaced / Previous Device Card
                if (eventModel.hasDisplaced) {
                    itemBinding.cardDisplacedDevice.isVisible = true
                    itemBinding.tvDisplacedPlatform.text = eventModel.displacedPlatform
                    itemBinding.tvDisplacedDeviceName.text = eventModel.displacedDeviceName
                    itemBinding.tvDisplacedDeviceId.text = eventModel.displacedDeviceId
                    itemBinding.tvDisplacedDeviceId.isVisible = eventModel.displacedDeviceId.isNotBlank()
                    itemBinding.tvDisplacedLastSeen.text = eventModel.displacedLastSeen
                    itemBinding.tvDisplacedLastSeen.isVisible = eventModel.displacedLastSeen.isNotBlank()
                    itemBinding.tvDisplacedGps.text = eventModel.displacedGps
                    itemBinding.tvDisplacedGps.isVisible = eventModel.displacedGps.isNotBlank()
                    itemBinding.tvDisplacedDistance.text = eventModel.displacedDistance
                    itemBinding.tvDisplacedDistance.isVisible = eventModel.displacedDistance.isNotBlank()

                    if (eventModel.displacedWithinGeofence != null) {
                        itemBinding.tvDisplacedGeofenceBadge.isVisible = true
                        if (eventModel.displacedWithinGeofence) {
                            itemBinding.tvDisplacedGeofenceBadge.text = "Inside Allowed Geofence"
                            itemBinding.tvDisplacedGeofenceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                            itemBinding.tvDisplacedGeofenceBadge.setTextColor(Color.parseColor("#2E7D32"))
                        } else {
                            itemBinding.tvDisplacedGeofenceBadge.text = "Outside Allowed Geofence"
                            itemBinding.tvDisplacedGeofenceBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFEBEE"))
                            itemBinding.tvDisplacedGeofenceBadge.setTextColor(Color.parseColor("#C62828"))
                        }
                    } else {
                        itemBinding.tvDisplacedGeofenceBadge.isVisible = false
                    }

                    itemBinding.tvDisplacedStatus.text = eventModel.displacedStatus
                } else {
                    itemBinding.cardDisplacedDevice.isVisible = false
                }

                // Collapsible Technical Diagnostic JSON (hidden by default)
                val diagnosticJson = unwrapDiagnosticDetails(item.newValue)
                itemBinding.tvDiagnosticText.text = formatDiagnosticDetails(diagnosticJson)
                itemBinding.tvDiagnosticText.visibility = View.GONE
                itemBinding.tvToggleRawJson.text = "⚙️ Technical Diagnostic JSON"
                itemBinding.tvToggleRawJson.setOnClickListener {
                    if (itemBinding.tvDiagnosticText.isVisible) {
                        itemBinding.tvDiagnosticText.visibility = View.GONE
                        itemBinding.tvToggleRawJson.text = "⚙️ Technical Diagnostic JSON"
                    } else {
                        itemBinding.tvDiagnosticText.visibility = View.VISIBLE
                        itemBinding.tvToggleRawJson.text = "⚙️ Hide Diagnostic JSON"
                    }
                }
            }
        }
    }
}
