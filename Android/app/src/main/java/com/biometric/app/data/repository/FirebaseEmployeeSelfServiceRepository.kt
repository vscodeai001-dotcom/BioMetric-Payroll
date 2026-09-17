package com.biometric.app.data.repository

import com.biometric.app.api.AttendanceDayDto
import com.biometric.app.api.EmployeeDashboardResponse
import com.biometric.app.api.FbpDto
import com.biometric.app.api.FbpRequest
import com.biometric.app.api.LeaveDto
import com.biometric.app.api.MoneyEntryDto
import com.biometric.app.api.PayslipDto
import com.biometric.app.api.RegularizationCreateRequest
import com.biometric.app.api.RegularizationDto
import com.biometric.app.api.ResignationCreateRequest
import com.biometric.app.api.ResignationDto
import com.biometric.app.api.ShiftDto
import com.biometric.app.api.TaxDeclarationDto
import com.biometric.app.api.TaxDeclarationRequest
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.AdvancePayment
import com.biometric.app.data.entity.Attendance
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.FeatureSettings
import com.biometric.app.data.entity.LeaveRequest
import com.biometric.app.data.entity.RegularizationRequest
import com.biometric.app.data.entity.ResignationRequest
import com.biometric.app.sync.FirebaseSyncManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class FirebaseEmployeeSelfServiceRepository @Inject constructor(
    private val firebaseSync: FirebaseSyncManager,
    private val sessionStore: MobileSessionStore
) {
    private val auth = FirebaseAuth.getInstance()
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private fun ownerRef(): DatabaseReference =
        firebaseSync.getOwnerRef()
            ?: throw IllegalStateException("Firebase employee session is not initialized")

    fun currentEmployeeId(): Int = sessionStore.employeeId()

    fun formatEmployeeDate(millis: Long): String = formatDate(millis)

    private fun employeeId(): String {
        val id = sessionStore.employeeId()
        if (id <= 0) throw IllegalStateException("Employee session is missing")
        return id.toString()
    }

    private suspend fun employee(): Employee? {
        val id = employeeId()
        val employeesRef = ownerRef().child("employees")

        // Firebase data migrated from the Web database can contain numeric
        // employeeId values, while the Android Employee model uses String.
        // Never use getValue(Employee::class.java) here because Firebase's
        // automatic mapper is strict about String/Int mismatches.
        // Canonical Firebase key: owners/{ownerUid}/employees/{employeeId}.
        // 1100-S security/parity boundary: Employee sessions must resolve only
        // through the canonical employee key. Do not enumerate the owner
        // employees collection as a fallback because Firebase rules correctly
        // restrict Staff users to their own record. Legacy/generated-key
        // fallbacks belong to migration tooling, not the Employee runtime path.
        val direct = employeesRef.child(id).get().await()
        return direct.takeIf { it.exists() }?.toEmployee(id)
    }

    suspend fun employeeProfile(): Employee? = employee()

    suspend fun employeeDisplayName(): String =
        employee()?.name?.trim().orEmpty()

    suspend fun nextPunchType(): String {
        val id = employeeId()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val punches = readList("attendance_punches") { it.toAttendancePunch() }.filter { it.staffId == id && (it.date == today || formatDate(it.timestamp) == today) }
            .sortedBy { it.timestamp }
        val last = punches.lastOrNull()?.type?.uppercase(Locale.US)
        return if (last == "IN") "OUT" else "IN"
    }

    fun changesFlow(): Flow<Unit> = callbackFlow {
        val employeeKey = employeeId()
        val ref = firebaseSync.getOwnerRef()?.child("employees")?.child(employeeKey)
        if (ref == null) {
            close()
            return@callbackFlow
        }
        // 1100-S: listen only to the authenticated Employee record. This keeps
        // realtime parity with Web while avoiding owner-wide data invalidation
        // and avoiding a Staff client dependency on collection enumeration.
        val listener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { trySend(Unit) }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) { trySend(Unit) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    private fun DataSnapshot.valueOf(name: String): Any? {
        val exact = child(name)
        if (exact.exists()) return exact.value
        val lower = child(name.replaceFirstChar { it.lowercase() })
        if (lower.exists()) return lower.value
        val upper = child(name.replaceFirstChar { it.uppercase() })
        if (upper.exists()) return upper.value
        return null
    }

    private fun DataSnapshot.bool(name: String, default: Boolean = false): Boolean =
        when (val v = valueOf(name)) {
            is Boolean -> v
            else -> v?.toString()?.toBooleanStrictOrNull() ?: default
        }

    private fun DataSnapshot.float(name: String): Float = double(name).toFloat()

    private fun DataSnapshot.toEmployee(fallbackId: String): Employee = Employee(
        employeeId = string("employeeId")?.takeIf { it.isNotBlank() } ?: valueOf("employeeId")?.toString()?.toLongOrNull()?.toString() ?: fallbackId,
        shopId = string("shopId").orEmpty(),
        name = string("name").orEmpty(),
        phone = string("phone").orEmpty(),
        email = string("email"),
        biometricId = string("biometricId").orEmpty(),
        role = string("role") ?: "Staff",
        salaryType = string("salaryType") ?: "MONTHLY_FIXED",
        salaryRate = double("salaryRate"),
        paidLeaveBalance = double("paidLeaveBalance"),
        sickLeaveBalance = double("sickLeaveBalance"),
        salaryCalculationMethod = string("salaryCalculationMethod") ?: "Pro-Rata Hourly",
        shiftStart = string("shiftStart") ?: "10:00",
        shiftEnd = string("shiftEnd") ?: "22:00",
        breakHours = double("breakHours"),
        shift2Start = string("shift2Start"),
        shift2End = string("shift2End"),
        weekendShiftStart = string("weekendShiftStart"),
        weekendShiftEnd = string("weekendShiftEnd"),
        weekendBreakHours = valueOf("weekendBreakHours")?.toString()?.toDoubleOrNull(),
        weekendShift2Start = string("weekendShift2Start"),
        weekendShift2End = string("weekendShift2End"),
        compOffDayOfWeek = valueOf("compOffDayOfWeek")?.toString()?.toIntOrNull(),
        otRule = string("otRule") ?: "No Overtime",
        otFlatRate = double("otFlatRate"),
        otRateMultiplier = double("otRateMultiplier").takeIf { it != 0.0 } ?: 1.0,
        dailyAllowance = double("dailyAllowance"),
        nightShiftAllowance = double("nightShiftAllowance"),
        allowanceEffectiveDate = long("allowanceEffectiveDate"),
        isActive = bool("isActive", true),
        hireDate = long("hireDate").takeIf { it > 0 } ?: System.currentTimeMillis(),
        dob = long("dob").takeIf { it > 0 },
        terminateDate = long("terminateDate").takeIf { it > 0 },
        enableShiftRotation = bool("enableShiftRotation"),
        rotationGroup = string("rotationGroup"),
        shiftRotationPattern = string("shiftRotationPattern"),
        createdAt = long("createdAt").takeIf { it > 0 } ?: System.currentTimeMillis(),
        isBonusEligibleRule = bool("isBonusEligibleRule", true),
        isPaidLeaveEligibleRule = bool("isPaidLeaveEligibleRule", true),
        paidLeaveOnWeekdays = bool("paidLeaveOnWeekdays", true),
        paidLeaveOnWeekends = bool("paidLeaveOnWeekends"),
        bankAccountNumber = string("bankAccountNumber"),
        bankIfscCode = string("bankIfscCode"),
        bankName = string("bankName"),
        uanNumber = string("uanNumber"),
        esiNumber = string("esiNumber"),
        enablePf = bool("enablePf"),
        enableEsi = bool("enableEsi"),
        tdsRatePercent = double("tdsRatePercent"),
        lastActive = long("lastActive").takeIf { it > 0 },
        lastModified = long("lastModified").takeIf { it > 0 } ?: System.currentTimeMillis()
    )

    private fun DataSnapshot.toAttendance(): Attendance = Attendance(
        attendanceId = string("attendanceId") ?: key.orEmpty(),
        employeeId = string("employeeId") ?: valueOf("employeeId")?.toString()?.toLongOrNull()?.toString().orEmpty(),
        shopId = string("shopId").orEmpty(),
        checkInTime = long("checkInTime"),
        checkOutTime = long("checkOutTime").takeIf { it > 0 },
        type = string("type") ?: "WORK",
        hoursWorked = double("hoursWorked"),
        shiftStart = string("shiftStart") ?: "10:00",
        shiftEnd = string("shiftEnd") ?: "22:00",
        shift2Start = string("shift2Start"),
        shift2End = string("shift2End"),
        breakHours = double("breakHours"),
        salaryType = string("salaryType") ?: "MONTHLY_FIXED",
        salaryRate = double("salaryRate"),
        note = string("note"),
        synced = bool("synced", true),
        lateDeduction = double("lateDeduction"),
        otHours = double("otHours"),
        createdAt = long("createdAt")
    )

    private fun DataSnapshot.toAttendancePunch(): AttendancePunch = AttendancePunch(
        punchId = string("punchId") ?: key.orEmpty(),
        staffId = string("staffId") ?: valueOf("staffId")?.toString()?.toLongOrNull()?.toString().orEmpty(),
        date = string("date").orEmpty(),
        type = string("type") ?: "IN",
        timestamp = long("timestamp"),
        latitude = double("latitude"),
        longitude = double("longitude"),
        accuracy = float("accuracy"),
        geofenceId = string("geofenceId"),
        distanceFromGeofence = double("distanceFromGeofence"),
        photoId = string("photoId"),
        deviceId = string("deviceId").orEmpty(),
        source = string("source") ?: "GEOFENCE",
        status = string("status") ?: "PENDING"
    )

    private fun DataSnapshot.toLeaveRequest(): LeaveRequest = LeaveRequest(
        id = string("id") ?: key.orEmpty(),
        staffId = string("staffId") ?: valueOf("staffId")?.toString()?.toLongOrNull()?.toString().orEmpty(),
        staffName = string("staffName").orEmpty(),
        leaveType = string("leaveType") ?: "Casual Leave",
        startDate = long("startDate"),
        endDate = long("endDate"),
        reason = string("reason").orEmpty(),
        status = string("status") ?: "Pending",
        adminNotes = string("adminNotes"),
        isHalfDay = bool("isHalfDay"),
        createdAt = long("createdAt")
    )

    private fun DataSnapshot.toAdvancePayment(): AdvancePayment = AdvancePayment(
        advanceId = string("advanceId") ?: key.orEmpty(),
        employeeId = string("employeeId") ?: valueOf("employeeId")?.toString()?.toLongOrNull()?.toString().orEmpty(),
        shopId = string("shopId").orEmpty(),
        amount = double("amount"),
        date = long("date"),
        isRecovered = bool("isRecovered"),
        recoveryPaymentId = string("recoveryPaymentId")
    )

    private fun DataSnapshot.toRegularizationRequest(): RegularizationRequest = RegularizationRequest(
        id = string("id") ?: key.orEmpty(),
        staffId = string("staffId") ?: valueOf("staffId")?.toString()?.toLongOrNull()?.toString().orEmpty(),
        staffName = string("staffName").orEmpty(),
        date = string("date").orEmpty(),
        punchType = string("punchType") ?: "IN",
        originalTime = long("originalTime").takeIf { it > 0 },
        requestedTime = long("requestedTime"),
        reason = string("reason").orEmpty(),
        status = string("status") ?: "Pending",
        adminRemarks = string("adminRemarks"),
        submittedAt = long("submittedAt")
    )

    private fun DataSnapshot.toResignationRequest(): ResignationRequest = ResignationRequest(
        requestId = string("requestId") ?: key.orEmpty(),
        employeeId = string("employeeId") ?: valueOf("employeeId")?.toString()?.toLongOrNull()?.toString().orEmpty(),
        submissionDate = long("submissionDate"),
        desiredLastWorkingDay = long("desiredLastWorkingDay"),
        reason = string("reason"),
        status = string("status") ?: "Pending",
        approvedLastWorkingDay = long("approvedLastWorkingDay").takeIf { it > 0 },
        adminRemarks = string("adminRemarks"),
        isSettled = bool("isSettled")
    )

    private suspend fun <T : Any> readList(table: String, mapper: (DataSnapshot) -> T?): List<T> {
        // 1014 security parity: employee reads must be server-filtered by the
        // same employee key enforced by Firebase RTDB rules. Admin screens use
        // their own repositories and are not routed through this self-service class.
        val id = sessionStore.employeeId()
        when (table) {
            "payroll_history", "salary_snapshots" -> requireFeatureAllowed("payslip")
            "attendance", "attendance_punches", "daily_summaries" -> requireFeatureAllowed("attendance")
            "shift_schedules" -> requireFeatureAllowed("shift")
            "leave_requests" -> requireFeatureAllowed("leave")
            "advance_payments" -> requireFeatureAllowed("advance")
            "bonus_records" -> requireFeatureAllowed("bonus")
            "regularizations" -> requireFeatureAllowed("regularization")
            "resignation_requests" -> requireFeatureAllowed("resignation")
            "tax_declarations" -> requireFeatureAllowed("tax")
            "fbp_declarations" -> requireFeatureAllowed("fbp")
        }
        val query = when (table) {
            "payroll_history", "tax_declarations", "fbp_declarations", "bonus_records" ->
                ownerRef().child(table).orderByChild("employeeId").equalTo(id.toDouble())
            "attendance_punches", "regularizations", "leave_requests" ->
                ownerRef().child(table).orderByChild("staffId").equalTo(id.toString())
            "attendance", "salary_snapshots", "daily_summaries", "shift_schedules", "salary_payments", "resignation_requests", "advance_payments" ->
                ownerRef().child(table).orderByChild("employeeId").equalTo(id.toDouble())
            else -> ownerRef().child(table)
        }
        return query.get().await().children.mapNotNull { mapper(it) }
    }

    private fun DataSnapshot.string(name: String): String? =
        valueOf(name)?.toString()?.takeIf { it.isNotBlank() }

    private fun DataSnapshot.long(name: String): Long {
        val value = child(name).value ?: child(name.replaceFirstChar { it.uppercase() }).value
        return when (value) {
            is Number -> value.toLong()
            else -> value?.toString()?.toLongOrNull()
                ?: value?.toString()?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: 0L
        }
    }

    private fun DataSnapshot.int(name: String): Int = long(name).toInt()

    private fun DataSnapshot.double(name: String): Double {
        val value = child(name).value ?: child(name.replaceFirstChar { it.uppercase() }).value
        return when (value) {
            is Number -> value.toDouble()
            else -> value?.toString()?.toDoubleOrNull() ?: 0.0
        }
    }

    private fun DataSnapshot.doubleAny(vararg names: String): Double =
        names.asSequence().mapNotNull { name ->
            val value = child(name).value
            when (value) {
                is Number -> value.toDouble()
                else -> value?.toString()?.toDoubleOrNull()
            }
        }.firstOrNull() ?: 0.0

    private fun DataSnapshot.intAny(vararg names: String): Int =
        names.asSequence().mapNotNull { name ->
            val value = child(name).value
            when (value) {
                is Number -> value.toInt()
                else -> value?.toString()?.toIntOrNull()
            }
        }.firstOrNull() ?: 0

    private fun DataSnapshot.boolAny(default: Boolean, vararg names: String): Boolean {
        names.forEach { name ->
            if (child(name).exists()) {
                return when (val value = child(name).value) {
                    is Boolean -> value
                    else -> value?.toString()?.toBooleanStrictOrNull() ?: default
                }
            }
        }
        return default
    }

    suspend fun featureSettings(): FeatureSettings {
        val snapshot = ownerRef().child("feature_settings").child("1").get().await()
        return runCatching {
            com.google.gson.Gson().fromJson(
                com.google.gson.Gson().toJson(snapshot.value),
                FeatureSettings::class.java
            )
        }.getOrNull() ?: FeatureSettings()
    }

    private suspend fun requireFeatureAllowed(key: String) {
        val f = featureSettings()
        val allowed = when (key) {
            "attendance" -> f.employeeCanViewAttendance
            "leave" -> f.enableLeaveManagement && f.employeeCanViewLeave
            "payslip" -> f.enablePayroll && f.employeeCanViewPayslip
            "advance" -> f.enableSalaryAdvance && f.employeeCanViewAdvance
            "bonus" -> f.enableBonusManagement && f.employeeCanViewBonus
            "regularization" -> f.enablePunchCorrection && f.enableRegularizationRequest
            "resignation" -> f.enableResignationModule && f.employeeCanViewResignation
            "shift" -> f.enableShiftScheduling && f.employeeCanViewShifts
            "tax" -> f.enableTaxDeclarations && f.employeeCanViewTax
            "fbp" -> f.enableFlexibleBenefits
            "reports" -> f.enableCustomReporting && f.employeeCanViewReports
            else -> true
        }
        if (!allowed) throw SecurityException("This employee self-service feature is disabled by the administrator")
    }

    suspend fun dashboard(): EmployeeDashboardResponse {
        val emp = employee() ?: throw IllegalStateException("Employee record not found")
        val settings = ownerRef().child("company_settings").child("1").get().await()
        val features = ownerRef().child("feature_settings").child("1").get().await()
        val canViewPayslip = features.boolAny(true, "employeeCanViewPayslip", "employee_can_view_payslip", "EmployeeCanViewPayslip") &&
            features.boolAny(true, "enablePayroll", "enable_payroll", "EnablePayroll")
        val latest = if (canViewPayslip) payrollHistory().firstOrNull() else null

        // Firebase is the cross-platform synchronization source. Company and
        // feature settings must be read from the same owner node as the employee
        // instead of falling back to a separate Web/API database. Support the
        // canonical camelCase contract plus legacy snake_case keys created by
        // earlier Web syncs.
        val officeLatitude = settings.doubleAny("officeLatitude", "office_latitude", "OfficeLatitude")
        val officeLongitude = settings.doubleAny("officeLongitude", "office_longitude", "OfficeLongitude")
        val geoRadiusMeters = settings.intAny("geoRadiusMeters", "geo_radius_meters", "GeoRadiusMeters")
            .takeIf { it > 0 } ?: 1000
        val enableGeoFencing = features.boolAny(true, "enableGeoFencing", "enable_geo_fencing", "EnableGeoFencing")
        val enableDualAttendance = features.boolAny(false, "enableDualAttendance", "enable_dual_attendance", "EnableDualAttendance")
        val enableAutomaticGeofencePunching = features.boolAny(
            false,
            "enableAutomaticGeofencePunching",
            "enable_automatic_geofence_punching",
            "EnableAutomaticGeofencePunching"
        )

        // Leave balances are part of the canonical Employee Firebase record.
        // Never fabricate a default balance when the employee record is loaded.
        val paid = emp.paidLeaveBalance
        val sick = emp.sickLeaveBalance

        return EmployeeDashboardResponse(
            success = true,
            employeeId = emp.employeeId.toIntOrNull() ?: sessionStore.employeeId(),
            name = emp.name,
            email = emp.email.orEmpty().ifBlank { sessionStore.userEmail() },
            monthlySalary = emp.salaryRate,
            paidLeaveBalance = paid,
            sickLeaveBalance = sick,
            latestPayslip = latest,
            officeLatitude = officeLatitude,
            officeLongitude = officeLongitude,
            geoRadiusMeters = geoRadiusMeters,
            enableGeoFencing = enableGeoFencing,
            enableDualAttendance = enableDualAttendance,
            enableAutomaticGeofencePunching = enableAutomaticGeofencePunching,
            role = emp.role,
            dob = emp.dob?.let(::formatDate),
            hireDate = emp.hireDate.takeIf { it > 0 }?.let(::formatDate),
            shiftStartTime = emp.shiftStart,
            shiftEndTime = emp.shiftEnd,
            uan = emp.uanNumber,
            esiNumber = emp.esiNumber,
            bankName = emp.bankName,
            bankAccountNumber = emp.bankAccountNumber,
            bankIfscCode = emp.bankIfscCode
        )
    }

    suspend fun attendance(from: String, to: String): List<AttendanceDayDto> {
        val emp = employee() ?: return emptyList()
        val start = LocalDate.parse(from, dateFormatter)
        val end = LocalDate.parse(to, dateFormatter)
        val attendance = readList("attendance") { it.toAttendance() }
            .filter { it.employeeId == emp.employeeId }
        val punches = readList("attendance_punches") { runCatching { it.getValue(AttendancePunch::class.java) }.getOrNull() }
            .filter { it.staffId == emp.employeeId }
        val summaries = ownerRef().child("daily_summaries")
            .orderByChild("employeeId")
            .equalTo(sessionStore.employeeId().toDouble())
            .get().await().children
            .associateBy { it.string("shiftDate").orEmpty() }

        return generateSequence(start) { if (it < end) it.plusDays(1) else null }.map { day ->
            val date = day.format(dateFormatter)
            val dayAttendance = attendance.filter { formatDate(it.checkInTime) == date }
            val dayPunches = punches.filter { it.date == date || formatDate(it.timestamp) == date }.sortedBy { it.timestamp }
            val summary = summaries[date]
            val scheduled = summary?.long("scheduledShiftDurationMs")?.div(3_600_000.0)
                ?: scheduledHours(emp.shiftStart, emp.shiftEnd, emp.breakHours)
            val worked = dayAttendance.sumOf {
                if (it.hoursWorked > 0) it.hoursWorked
                else it.checkOutTime?.let { out ->
                    if (out > it.checkInTime) (out - it.checkInTime) / 3_600_000.0 else 0.0
                } ?: 0.0
            }
            val status = summary?.string("status")?.takeIf { it.isNotBlank() } ?: when {
                dayPunches.isEmpty() && dayAttendance.isEmpty() -> "Absent"
                dayPunches.any { it.type.equals("IN", true) } && dayPunches.any { it.type.equals("OUT", true) } -> "Present"
                dayPunches.isNotEmpty() -> "Missing Punch"
                else -> "Present"
            }
            AttendanceDayDto(
                date = date,
                status = status,
                scheduledHours = scheduled,
                workedHours = worked,
                overtime = formatDuration(summary?.long("totalOvertimeMs") ?: 0),
                penalty = formatDuration(summary?.long("totalPenaltyMs") ?: 0),
                punches = dayPunches.map {
                    com.biometric.app.api.PunchDto(
                        time = formatTime(it.timestamp),
                        type = it.type,
                        source = it.source,
                        approved = !it.status.equals("REJECTED", true)
                    )
                }
            )
        }.toList()
    }

    suspend fun payslips(): List<PayslipDto> =
        payrollHistory().sortedWith(compareByDescending<PayslipDto> { it.year }.thenByDescending { it.month })

    private suspend fun payrollHistory(): List<PayslipDto> {
        val id = sessionStore.employeeId()
        return readList("payroll_history") { s ->
            if (s.int("employeeId") != id) return@readList null
            val pf = s.double("pfDeduction")
            val esi = s.double("esiDeduction")
            val pt = s.double("ptDeduction")
            val tds = s.double("tdsDeduction")
            val advance = s.double("deductionsAdvance")
            val hours = s.double("deductionsHours")
            PayslipDto(
                payrollId = s.int("payrollId"),
                month = s.int("payMonth"),
                year = s.int("payYear"),
                baseSalary = s.double("baseSalary"),
                overtimePay = s.double("overtimePay"),
                bonus = s.double("bonus"),
                advanceDeduction = advance,
                pfDeduction = pf,
                esiDeduction = esi,
                ptDeduction = pt,
                tdsDeduction = tds,
                netSalary = s.double("netSalary"),
                hourlyRate = s.double("hourlyRate"),
                totalHoursWorked = s.double("totalHoursWorked"),
                totalDeductions = pf + esi + pt + tds + advance + hours
            )
        }
    }

    suspend fun leaves(): List<LeaveDto> {
        val id = employeeId()
        val requests = readList("leave_requests") { it.toLeaveRequest() }
            .filter { it.staffId == id }
        val result = mutableListOf<LeaveDto>()
        requests.forEach { req ->
            val start = LocalDate.ofEpochDay(req.startDate / 86_400_000L)
            val end = LocalDate.ofEpochDay(req.endDate / 86_400_000L)
            val days = ChronoUnit.DAYS.between(start, end).toInt().coerceAtLeast(0)
            for (offset in 0..days) {
                val date = start.plusDays(offset.toLong()).format(dateFormatter)
                result += LeaveDto(
                    id = stableIntId("${req.id}:$date"),
                    date = date,
                    leaveType = req.leaveType,
                    halfDay = req.isHalfDay,
                    approved = req.status.equals("Approved", true),
                    notes = req.adminNotes ?: req.reason
                )
            }
        }
        return result.sortedByDescending { it.date }
    }

    suspend fun createLeave(leaveDate: String, leaveType: String, isHalfDay: Boolean, notes: String?) {
        requireFeatureAllowed("leave")
        val emp = employee() ?: throw IllegalStateException("Employee record not found")
        val id = UUID.randomUUID().toString()
        val date = LocalDate.parse(leaveDate, dateFormatter).toEpochDay() * 86_400_000L
        firebaseSync.pushLeaveRequest(
            LeaveRequest(id, emp.employeeId, emp.name, leaveType, date, date, notes.orEmpty(), "Pending", null, isHalfDay, System.currentTimeMillis())
        )
        notifyPortalChanged()
    }

    suspend fun advances(): List<MoneyEntryDto> {
        val id = employeeId()
        return readList("advance_payments") { it.toAdvancePayment() }
            .filter { it.employeeId == id }.sortedByDescending { it.date }
            .map {
                MoneyEntryDto(
                    id = stableIntId(it.advanceId),
                    date = formatDate(it.date),
                    amount = it.amount,
                    type = "Salary Advance",
                    description = null,
                    paid = it.isRecovered,
                    employeeId = it.employeeId.toIntOrNull() ?: 0
                )
            }
    }

    suspend fun createAdvance(amount: Double, reason: String) {
        requireFeatureAllowed("advance")
        val emp = employee() ?: throw IllegalStateException("Employee record not found")
        val id = UUID.randomUUID().toString()
        ownerRef().child("advance_payments").child(id).setValue(
            mapOf(
                "advanceId" to id, "employeeId" to emp.employeeId, "shopId" to emp.shopId,
                "amount" to amount, "date" to System.currentTimeMillis(),
                "isRecovered" to false, "recoveryPaymentId" to null, "reason" to reason
            )
        ).await()
        firebaseSync.notifyRealtimeChanged("AdvancePayment", "ADDED", id)
        notifyPortalChanged()
    }

    suspend fun bonuses(): List<MoneyEntryDto> =
        ownerRef().child("bonus_records")
            .orderByChild("employeeId")
            .equalTo(sessionStore.employeeId().toDouble())
            .get().await().children.mapNotNull { s ->
            if (s.int("employeeId") != sessionStore.employeeId()) return@mapNotNull null
            MoneyEntryDto(
                id = s.int("bonusId"),
                date = formatDate(s.long("bonusDate")),
                amount = s.double("amount"),
                type = "Performance Bonus",
                description = s.string("description"),
                paid = s.int("payrollIdPaid") > 0,
                employeeId = s.int("employeeId")
            )
        }.sortedByDescending { it.date }

    suspend fun regularizations(): List<RegularizationDto> =
        readList("regularizations") { it.toRegularizationRequest() }
            .filter { it.staffId == employeeId() }
            .sortedByDescending { it.submittedAt }
            .map {
                RegularizationDto(stableIntId(it.id), it.date, it.punchType.equals("IN", true),
                    formatTime(it.requestedTime), it.reason, it.status, it.adminRemarks,
                    it.status.equals("Rejected", true))
            }

    suspend fun createRegularization(request: RegularizationCreateRequest) {
        requireFeatureAllowed("regularization")
        val emp = employee() ?: throw IllegalStateException("Employee record not found")
        // Keep the Firebase key numeric so the existing Web SQL compatibility
        // projection can materialize the request without introducing a second
        // regularization identity schema. Negative IDs are reserved for
        // client-created Firebase-first requests and cannot collide with the
        // normal positive SQL identity sequence.
        val id = (-System.currentTimeMillis()).toString()
        firebaseSync.pushRegularization(
            RegularizationRequest(
                id, emp.employeeId, emp.name, request.dateOfPunch,
                if (request.isInPunch) "IN" else "OUT", null,
                parseDateTime(request.dateOfPunch, request.punchTimeNew),
                request.reason, "Pending", null, System.currentTimeMillis()
            )
        )
        notifyPortalChanged()
    }

    suspend fun resignation(): ResignationDto? =
        readList("resignation_requests") { it.toResignationRequest() }
            .filter { it.employeeId == employeeId() }.maxByOrNull { it.submissionDate }?.let {
                ResignationDto(stableIntId(it.requestId), formatDate(it.submissionDate),
                    formatDate(it.desiredLastWorkingDay), it.reason.orEmpty(), it.status,
                    it.approvedLastWorkingDay?.let(::formatDate), it.adminRemarks, it.isSettled)
            }

    suspend fun createResignation(request: ResignationCreateRequest) {
        requireFeatureAllowed("resignation")
        val emp = employee() ?: throw IllegalStateException("Employee record not found")
        val id = UUID.randomUUID().toString()
        firebaseSync.pushResignationRequest(
            ResignationRequest(id, emp.employeeId, System.currentTimeMillis(),
                parseDate(request.desiredLastWorkingDay), request.reason, "Pending", null, null, false)
        )
        notifyPortalChanged()
    }

    suspend fun tax(financialYear: Int): TaxDeclarationDto? =
        readList("tax_declarations") { s ->
            if (s.int("employeeId") != sessionStore.employeeId() || s.int("financialYear") != financialYear) null
            else TaxDeclarationDto(
                declarationId = s.int("declarationId"),
                financialYear = s.int("financialYear"),
                regime = s.string("regime") ?: "New",
                section80C = s.double("section80C"),
                section80D = s.double("section80D"),
                hraRentPaid = s.double("hraRentPaid"),
                otherExemptions = s.double("otherExemptions"),
                status = s.string("status") ?: "Pending",
                adminRemarks = s.string("adminRemarks"),
                employeeId = s.int("employeeId")
            )
        }.maxByOrNull { it.declarationId }

    suspend fun saveTax(request: TaxDeclarationRequest): TaxDeclarationDto {
        requireFeatureAllowed("tax")
        val employeeId = sessionStore.employeeId()
        val declarationId = nextIntId("tax_declaration_id", "tax_declarations", "declarationId")
        ownerRef().child("tax_declarations").child(declarationId.toString()).setValue(
            mapOf(
                "declarationId" to declarationId, "employeeId" to employeeId,
                "financialYear" to request.financialYear, "regime" to request.regime,
                "section80C" to request.section80C, "section80D" to request.section80D,
                "hraRentPaid" to request.hraRentPaid, "otherExemptions" to request.otherExemptions,
                "status" to "Pending", "adminRemarks" to null,
                "submissionDate" to System.currentTimeMillis(), "approvalDate" to null
            )
        ).await()
        firebaseSync.notifyRealtimeChanged("TaxDeclaration", "MODIFIED", declarationId.toString())
        notifyPortalChanged()
        return TaxDeclarationDto(
            declarationId = declarationId,
            financialYear = request.financialYear,
            regime = request.regime,
            section80C = request.section80C,
            section80D = request.section80D,
            hraRentPaid = request.hraRentPaid,
            otherExemptions = request.otherExemptions,
            status = "Pending",
            adminRemarks = null,
            employeeId = employeeId
        )
    }

    suspend fun fbp(financialYear: Int): List<FbpDto> =
        readList("fbp_declarations") { s ->
            if (s.int("employeeId") != sessionStore.employeeId() || s.int("financialYear") != financialYear) null
            else FbpDto(s.int("declarationId"), s.int("financialYear"), s.string("componentName").orEmpty(),
                s.double("annualAllocatedAmount"), s.double("monthlyAllocatedAmount"),
                s.string("status") ?: "Draft", s.string("adminRemarks"))
        }.sortedByDescending { it.declarationId }

    suspend fun saveFbp(request: FbpRequest): FbpDto {
        requireFeatureAllowed("fbp")
        val employeeId = sessionStore.employeeId()
        val declarationId = nextIntId("fbp_declaration_id", "fbp_declarations", "declarationId")
        val monthly = request.annualAllocatedAmount / 12.0
        ownerRef().child("fbp_declarations").child(declarationId.toString()).setValue(
            mapOf(
                "declarationId" to declarationId, "employeeId" to employeeId,
                "financialYear" to request.financialYear, "componentName" to request.componentName,
                "annualAllocatedAmount" to request.annualAllocatedAmount,
                "monthlyAllocatedAmount" to monthly, "status" to "Draft",
                "submissionDate" to System.currentTimeMillis(), "isActive" to true, "adminRemarks" to null
            )
        ).await()
        firebaseSync.notifyRealtimeChanged("FbpDeclaration", "MODIFIED", declarationId.toString())
        notifyPortalChanged()
        return FbpDto(declarationId, request.financialYear, request.componentName,
            request.annualAllocatedAmount, monthly, "Draft", null)
    }

    suspend fun shifts(month: String): List<ShiftDto> =
        readList("shift_schedules") { s ->
            if (s.int("employeeId") != sessionStore.employeeId()) return@readList null
            val date = s.string("shiftDate").orEmpty()
            if (!date.startsWith(month)) return@readList null
            val d = runCatching { LocalDate.parse(date, dateFormatter) }.getOrNull() ?: return@readList null
            ShiftDto(date, d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() },
                s.string("startTime").orEmpty(), s.string("endTime").orEmpty(), "Scheduled")
        }.sortedBy { it.date }

    private suspend fun nextIntId(counterName: String, table: String, field: String): Int {
        // 1015: allocate IDs atomically so two offline/reconnecting clients
        // cannot both observe the same counter and create the same numeric ID.
        // The existing table scan is retained as a migration-safe floor for
        // counters created before this transaction existed.
        val existing = ownerRef().child(table).get().await().children.maxOfOrNull { it.int(field) } ?: 0
        val ref = ownerRef().child("counters").child(counterName)
        return ref.runTransactionAwait { current ->
            val stored = (current.value as? Number)?.toInt() ?: current.value?.toString()?.toIntOrNull() ?: 0
            val next = maxOf(existing, stored) + 1
            current.value = next
        }
    }

    private suspend fun com.google.firebase.database.DatabaseReference.runTransactionAwait(
        handler: (com.google.firebase.database.MutableData) -> Unit
    ): Int = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        runTransaction(object : com.google.firebase.database.Transaction.Handler {
            override fun doTransaction(currentData: com.google.firebase.database.MutableData): com.google.firebase.database.Transaction.Result {
                return try {
                    handler(currentData)
                    com.google.firebase.database.Transaction.success(currentData)
                } catch (_: Exception) {
                    com.google.firebase.database.Transaction.abort()
                }
            }

            override fun onComplete(error: com.google.firebase.database.DatabaseError?, committed: Boolean, currentData: com.google.firebase.database.DataSnapshot?) {
                if (error != null) continuation.resumeWith(Result.failure(error.toException()))
                else if (!committed) continuation.resumeWith(Result.failure(IllegalStateException("Firebase counter transaction was not committed")))
                else {
                    val value = currentData?.value
                    val result = when (value) {
                        is Number -> value.toInt()
                        else -> value?.toString()?.toIntOrNull()
                    }
                    if (result == null) continuation.resumeWith(Result.failure(IllegalStateException("Firebase counter returned a non-numeric value")))
                    else continuation.resumeWith(Result.success(result))
                }
            }
        })
        continuation.invokeOnCancellation { /* Firebase transaction cannot be cancelled safely here. */ }
    }

    private suspend fun notifyPortalChanged() {
        val uid = auth.currentUser?.uid ?: return
        val eventId = UUID.randomUUID().toString().replace("-", "")
        ownerRef().child("employee_portal_changes").child(eventId).setValue(
            mapOf("eventId" to eventId, "employeeId" to sessionStore.employeeId(),
                "uid" to uid, "timestamp" to System.currentTimeMillis())
        ).await()
    }

    private fun stableIntId(value: String): Int = abs(value.hashCode()).coerceAtLeast(1)
    private fun parseDate(value: String): Long =
        runCatching { LocalDate.parse(value, dateFormatter).toEpochDay() * 86_400_000L }.getOrDefault(System.currentTimeMillis())

    private fun parseDateTime(date: String, time: String): Long =
        runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$date $time")?.time }
            .getOrNull() ?: parseDate(date)

    private fun formatDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
    private fun formatTime(millis: Long): String = if (millis <= 0) "--" else SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))

    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0)) / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
    }

    private fun scheduledHours(start: String, end: String, breakHours: Double): Double {
        val a = start.split(":").mapNotNull { it.toIntOrNull() }
        val b = end.split(":").mapNotNull { it.toIntOrNull() }
        if (a.size < 2 || b.size < 2) return 0.0
        var s = a[0] * 60 + a[1]
        var e = b[0] * 60 + b[1]
        if (e <= s) e += 1440
        return ((e - s) / 60.0 - breakHours).coerceAtLeast(0.0)
    }
}
