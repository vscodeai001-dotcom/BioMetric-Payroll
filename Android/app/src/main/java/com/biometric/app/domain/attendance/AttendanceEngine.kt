package com.biometric.app.domain.attendance

import com.biometric.app.data.entity.AttendancePunch
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

data class AttendanceResult(
    val date: String,
    val totalWorkDurationMinutes: Long = 0,
    val insideShiftDurationMinutes: Long = 0,
    val outsideShiftDurationMinutes: Long = 0,
    val lateArrivalMinutes: Long = 0,
    val earlyLeaveMinutes: Long = 0,
    val overtimeMinutes: Long = 0,
    val breakDurationMinutes: Long = 0,
    val breakPenaltyMinutes: Long = 0,
    val status: String = "ABSENT",
    val isOpenPunch: Boolean = false,
    val punches: List<AttendancePunch> = emptyList()
)

/**
 * AttendanceEngine: Ports logic from C# services (AttendanceCalculatorService, 
 * AttendancePunchProcessor, AttendanceBreakPenaltyService) to Android.
 */
object AttendanceEngine {
    private val INDIAN_ZONE = ZoneId.of("Asia/Kolkata")
    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Processes a list of punches for a given date and shift timing.
     */
    fun processAttendance(
        date: String,
        punches: List<AttendancePunch>,
        shiftStart: String, // format "HH:mm"
        shiftEnd: String,   // format "HH:mm"
        breakThresholdMinutes: Int = 60,
        now: Long = System.currentTimeMillis()
    ): AttendanceResult {
        if (punches.isEmpty()) return AttendanceResult(date)

        val sortedPunches = punches.filter { it.status != "REJECTED" }.sortedBy { it.timestamp }
        val isOpen = sortedPunches.size % 2 != 0 && sortedPunches.last().type in listOf("IN", "BREAK_IN")

        val zdtNow = Instant.ofEpochMilli(now).atZone(INDIAN_ZONE)
        
        // Time boundaries for the day
        val localDate = LocalDate.parse(date, DATE_FORMATTER)
        val shiftStartLocalTime = LocalTime.parse(shiftStart)
        val shiftEndLocalTime = LocalTime.parse(shiftEnd)

        val shiftStartInstant = ZonedDateTime.of(localDate, shiftStartLocalTime, INDIAN_ZONE).toInstant()
        var shiftEndInstant = ZonedDateTime.of(localDate, shiftEndLocalTime, INDIAN_ZONE).toInstant()
        
        // Handle night shifts (e.g., 22:00 to 06:00)
        if (shiftEndInstant.isBefore(shiftStartInstant) || shiftEndInstant == shiftStartInstant) {
            shiftEndInstant = shiftEndInstant.plus(Duration.ofDays(1))
        }

        val workSegments = mutableListOf<Pair<Instant, Instant>>()
        val breakSegments = mutableListOf<Pair<Instant, Instant>>()

        var tempIn: Instant? = null
        var tempBreakIn: Instant? = null

        sortedPunches.forEach { p ->
            val pInstant = Instant.ofEpochMilli(p.timestamp)
            when (p.type) {
                "IN" -> tempIn = pInstant
                "OUT" -> {
                    tempIn?.let { workSegments.add(it to pInstant) }
                    tempIn = null
                }
                "BREAK_IN" -> tempBreakIn = pInstant
                "BREAK_OUT" -> {
                    tempBreakIn?.let { breakSegments.add(it to pInstant) }
                    tempBreakIn = null
                }
            }
        }

        // Open punch handling: use current time as terminal point for active punches
        if (isOpen) {
            val last = sortedPunches.last()
            val lastInstant = Instant.ofEpochMilli(last.timestamp)
            if (last.type == "IN") workSegments.add(lastInstant to zdtNow.toInstant())
            else if (last.type == "BREAK_IN") breakSegments.add(lastInstant to zdtNow.toInstant())
        }

        var totalRawWorkMs = 0L
        workSegments.forEach { (start, end) ->
            totalRawWorkMs += Duration.between(start, end).toMillis()
        }

        var totalBreakMs = 0L
        breakSegments.forEach { (start, end) ->
            totalBreakMs += Duration.between(start, end).toMillis()
        }

        // Inside Shift calculation: Overlap of work segments with shift boundaries
        var insideShiftMs = 0L
        workSegments.forEach { (start, end) ->
            val overlapStart = if (start.isAfter(shiftStartInstant)) start else shiftStartInstant
            val overlapEnd = if (end.isBefore(shiftEndInstant)) end else shiftEndInstant
            if (overlapEnd.isAfter(overlapStart)) {
                insideShiftMs += Duration.between(overlapStart, overlapEnd).toMillis()
            }
        }

        // Subtract breaks that happened inside shift boundaries
        breakSegments.forEach { (start, end) ->
            val overlapStart = if (start.isAfter(shiftStartInstant)) start else shiftStartInstant
            val overlapEnd = if (end.isBefore(shiftEndInstant)) end else shiftEndInstant
            if (overlapEnd.isAfter(overlapStart)) {
                insideShiftMs -= Duration.between(overlapStart, overlapEnd).toMillis()
            }
        }

        val totalNetWorkMin = (totalRawWorkMs - totalBreakMs) / 60000
        val insideShiftMin = max(0L, insideShiftMs / 60000)
        val breakMin = totalBreakMs / 60000
        
        // OT: Any work done outside the defined shift hours
        val outsideShiftMin = max(0L, totalNetWorkMin - insideShiftMin)

        // Lateness & Early Leave detection
        val firstIn = sortedPunches.firstOrNull { it.type == "IN" }?.let { Instant.ofEpochMilli(it.timestamp) }
        val lastOut = if (isOpen && sortedPunches.last().type == "IN") zdtNow.toInstant() 
                      else sortedPunches.lastOrNull { it.type == "OUT" }?.let { Instant.ofEpochMilli(it.timestamp) }

        val lateMin = if (firstIn != null && firstIn.isAfter(shiftStartInstant.plusSeconds(60))) 
                      Duration.between(shiftStartInstant, firstIn).toMinutes() else 0L
        val earlyMin = if (lastOut != null && lastOut.isBefore(shiftEndInstant.minusSeconds(60))) 
                       Duration.between(lastOut, shiftEndInstant).toMinutes() else 0L

        // Break Penalty: Excess break time beyond threshold
        val breakPenalty = if (breakMin > breakThresholdMinutes) breakMin - breakThresholdMinutes else 0L

        return AttendanceResult(
            date = date,
            totalWorkDurationMinutes = totalNetWorkMin,
            insideShiftDurationMinutes = insideShiftMin,
            outsideShiftDurationMinutes = outsideShiftMin,
            lateArrivalMinutes = lateMin,
            earlyLeaveMinutes = earlyMin,
            overtimeMinutes = outsideShiftMin,
            breakDurationMinutes = breakMin,
            breakPenaltyMinutes = breakPenalty,
            status = calculateStatus(totalNetWorkMin, lateMin, earlyMin, insideShiftMin),
            isOpenPunch = isOpen,
            punches = sortedPunches
        )
    }

    private fun calculateStatus(netWorkMin: Long, lateMin: Long, earlyMin: Long, insideMin: Long): String {
        return when {
            netWorkMin < 30 -> "ABSENT"
            insideMin < 240 -> "HALF_DAY"
            lateMin > 30 || earlyMin > 30 -> "LATE_OR_EARLY"
            else -> "PRESENT"
        }
    }
}
