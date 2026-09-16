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

    private fun employeeId(): String {
        val id = sessionStore.employeeId()
        if (id <= 0) throw IllegalStateException("Employee session is missing")
        return id.toString()
    }

    private suspend fun employee(): Employee? {
        val id = employeeId()
        val direct = ownerRef().child("employees").child(id).get().await()
        if (direct.exists()) {
            return direct.getValue(Employee::class.java)?.also {
                if (it.employeeId.isBlank()) it.employeeId = id
            }
        }

        // The canonical Firebase contract uses employees/{employeeId}.
        // This fallback also supports records migrated with a legacy key,
        // but it is only attempted when the direct canonical key is absent.
        val email = sessionStore.userEmail().trim()
        if (email.isNotBlank()) {
            val byEmail = ownerRef().child("employees").get().await().children
                .firstOrNull {
                    it.child("email").getValue(String::class.java)?.equals(email, true) == true
                }
            if (byEmail != null) {
                return byEmail.getValue(Employee::class.java)?.also {
                    if (it.employeeId.isBlank()) it.employeeId = id
                }
            }
        }
        return null
    }

    suspend fun employeeProfile(): Employee? = employee()

    suspend fun employeeDisplayName(): String =
        employee()?.name?.trim().orEmpty()

    suspend fun nextPunchType(): String {
        val id = employeeId()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val punches = readList("attendance_punches") {
            runCatching { it.getValue(AttendancePunch::class.java) }.getOrNull()
        }.filter { it.staffId == id && (it.date == today || formatDate(it.timestamp) == today) }
            .sortedBy { it.timestamp }
        val last = punches.lastOrNull()?.type?.uppercase(Locale.US)
        return if (last == "IN") "OUT" else "IN"
    }

    fun changesFlow(): Flow<Unit> = callbackFlow {
        val ref = firebaseSync.getOwnerRef()
        if (ref == null) {
            close()
            return@callbackFlow
        }
        // The Employee screens must react to Admin/Web changes without SignalR.
        // Listening at the Firebase owner node gives the portal a native
        // Firebase realtime invalidation path for all self-service data.
        val listener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { trySend(Unit) }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) { trySend(Unit) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    private suspend fun <T : Any> readList(table: String, mapper: (DataSnapshot) -> T?): List<T> {
        val snapshot = ownerRef().child(table).get().await()
        return snapshot.children.mapNotNull { mapper(it) }
    }

    private fun DataSnapshot.string(name: String): String? =
        child(name).getValue(String::class.java)
            ?: child(name.replaceFirstChar { it.uppercase() }).getValue(String::class.java)

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

    suspend fun dashboard(): EmployeeDashboardResponse {
        val emp = employee() ?: throw IllegalStateException("Employee record not found")
        val latest = payrollHistory().firstOrNull()
        val settings = ownerRef().child("company_settings").child("1").get().await()

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
            officeLatitude = settings.double("officeLatitude"),
            officeLongitude = settings.double("officeLongitude"),
            geoRadiusMeters = settings.int("geoRadiusMeters").takeIf { it > 0 } ?: 1000,
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
        val attendance = readList("attendance") { runCatching { it.getValue(Attendance::class.java) }.getOrNull() }
            .filter { it.employeeId == emp.employeeId }
        val punches = readList("attendance_punches") { runCatching { it.getValue(AttendancePunch::class.java) }.getOrNull() }
            .filter { it.staffId == emp.employeeId }
        val summaries = ownerRef().child("daily_summaries").get().await().children
            .filter { it.int("employeeId") == sessionStore.employeeId() }
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
        val requests = readList("leave_requests") { runCatching { it.getValue(LeaveRequest::class.java) }.getOrNull() }
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
        return readList("advance_payments") { runCatching { it.getValue(AdvancePayment::class.java) }.getOrNull() }
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
        readList("bonus_records") { s ->
            if (s.int("employeeId") != sessionStore.employeeId()) return@readList null
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
        readList("regularizations") { runCatching { it.getValue(RegularizationRequest::class.java) }.getOrNull() }
            .filter { it.staffId == employeeId() }.sortedByDescending { it.submittedAt }
            .map {
                RegularizationDto(stableIntId(it.id), it.date, it.punchType.equals("IN", true),
                    formatTime(it.requestedTime), it.reason, it.status, it.adminRemarks,
                    it.status.equals("Rejected", true))
            }

    suspend fun createRegularization(request: RegularizationCreateRequest) {
        val emp = employee() ?: throw IllegalStateException("Employee record not found")
        val id = UUID.randomUUID().toString()
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
        readList("resignation_requests") { runCatching { it.getValue(ResignationRequest::class.java) }.getOrNull() }
            .filter { it.employeeId == employeeId() }.maxByOrNull { it.submissionDate }?.let {
                ResignationDto(stableIntId(it.requestId), formatDate(it.submissionDate),
                    formatDate(it.desiredLastWorkingDay), it.reason.orEmpty(), it.status,
                    it.approvedLastWorkingDay?.let(::formatDate), it.adminRemarks, it.isSettled)
            }

    suspend fun createResignation(request: ResignationCreateRequest) {
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
        val existing = ownerRef().child(table).get().await().children.maxOfOrNull { it.int(field) } ?: 0
        val ref = ownerRef().child("counters").child(counterName)
        val stored = ref.get().await().getValue(Int::class.java) ?: 0
        val next = maxOf(existing, stored) + 1
        ref.setValue(next).await()
        return next
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
