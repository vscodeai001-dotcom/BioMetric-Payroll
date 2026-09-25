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
        val isOdd = sortedPunches.size % 2 != 0
        val lastPunch = sortedPunches.last()
        val isLastOut = lastPunch.type.equals("OUT", ignoreCase = true) || lastPunch.type.equals("BREAK_OUT", ignoreCase = true)
        val isOpen = isOdd && !isLastOut

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

        val isShiftCrossed = zdtNow.toInstant().isAfter(shiftEndInstant)
        val isToday = localDate == zdtNow.toLocalDate()
        val isOvernightLive = shiftEndInstant.isAfter(ZonedDateTime.of(localDate, LocalTime.MIDNIGHT, INDIAN_ZONE).plusDays(1).toInstant()) && !isShiftCrossed
        val isLive = (isToday || isOvernightLive) && !isShiftCrossed

        val lastInstant = Instant.ofEpochMilli(lastPunch.timestamp)
        val terminalInstant = if (isLive) {
            if (zdtNow.toInstant().isAfter(lastInstant)) zdtNow.toInstant() else lastInstant
        } else {
            // Completed / historical day or shift time crossed: do NOT calculate throughout, credit up to scheduled ShiftEnd
            if (lastInstant.isBefore(shiftEndInstant)) shiftEndInstant else lastInstant
        }

        val workSegments = mutableListOf<Pair<Instant, Instant>>()
        val breakSegments = mutableListOf<Pair<Instant, Instant>>()
        val completedWorkSegments = mutableListOf<Pair<Instant, Instant>>()
        val completedBreakSegments = mutableListOf<Pair<Instant, Instant>>()

        var tempIn: Instant? = null
        var tempBreakIn: Instant? = null

        sortedPunches.forEach { p ->
            val pInstant = Instant.ofEpochMilli(p.timestamp)
            when (p.type.uppercase()) {
                "IN" -> tempIn = pInstant
                "OUT" -> {
                    tempIn?.let { 
                        workSegments.add(it to pInstant)
                        completedWorkSegments.add(it to pInstant)
                    }
                    tempIn = null
                }
                "BREAK_IN" -> tempBreakIn = pInstant
                "BREAK_OUT" -> {
                    tempBreakIn?.let { 
                        breakSegments.add(it to pInstant)
                        completedBreakSegments.add(it to pInstant)
                    }
                    tempBreakIn = null
                }
                else -> {
                    if (tempIn == null) {
                        tempIn = pInstant
                    } else {
                        workSegments.add(tempIn!! to pInstant)
                        completedWorkSegments.add(tempIn!! to pInstant)
                        tempIn = null
                    }
                }
            }
        }

        // Open punch handling: use terminal instant for active open work/break
        if (isOpen) {
            if (tempBreakIn != null || lastPunch.type.equals("BREAK_IN", ignoreCase = true)) {
                val start = tempBreakIn ?: lastInstant
                breakSegments.add(start to terminalInstant)
            } else {
                val start = tempIn ?: lastInstant
                workSegments.add(start to terminalInstant)
            }
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

        // Overtime (OT): STRICTLY calculated from completed IN -> OUT pairs with a valid OUT punch!
        // Open/odd punch segments never claim or calculate OT until a valid OUT is recorded.
        var completedRawWorkMs = 0L
        completedWorkSegments.forEach { (start, end) ->
            completedRawWorkMs += Duration.between(start, end).toMillis()
        }
        var completedBreakMs = 0L
        completedBreakSegments.forEach { (start, end) ->
            completedBreakMs += Duration.between(start, end).toMillis()
        }
        var completedInsideShiftMs = 0L
        completedWorkSegments.forEach { (start, end) ->
            val overlapStart = if (start.isAfter(shiftStartInstant)) start else shiftStartInstant
            val overlapEnd = if (end.isBefore(shiftEndInstant)) end else shiftEndInstant
            if (overlapEnd.isAfter(overlapStart)) {
                completedInsideShiftMs += Duration.between(overlapStart, overlapEnd).toMillis()
            }
        }
        completedBreakSegments.forEach { (start, end) ->
            val overlapStart = if (start.isAfter(shiftStartInstant)) start else shiftStartInstant
            val overlapEnd = if (end.isBefore(shiftEndInstant)) end else shiftEndInstant
            if (overlapEnd.isAfter(overlapStart)) {
                completedInsideShiftMs -= Duration.between(overlapStart, overlapEnd).toMillis()
            }
        }
        val completedNetWorkMin = (completedRawWorkMs - completedBreakMs) / 60000
        val completedInsideShiftMin = max(0L, completedInsideShiftMs / 60000)
        val validOvertimeMin = max(0L, completedNetWorkMin - completedInsideShiftMin)

        val insideShiftMin = max(0L, insideShiftMs / 60000)
        val breakMin = totalBreakMs / 60000
        val displayWorkMin = insideShiftMin + validOvertimeMin

        // Lateness & Early Leave detection
        val firstIn = sortedPunches.firstOrNull { !it.type.equals("OUT", ignoreCase = true) && !it.type.equals("BREAK_OUT", ignoreCase = true) }?.let { Instant.ofEpochMilli(it.timestamp) }
        val lastOut = if (isOpen) terminalInstant 
                      else sortedPunches.lastOrNull { it.type.equals("OUT", ignoreCase = true) }?.let { Instant.ofEpochMilli(it.timestamp) }

        val lateMin = if (firstIn != null && firstIn.isAfter(shiftStartInstant.plusSeconds(60))) 
                      Duration.between(shiftStartInstant, firstIn).toMinutes() else 0L
        val earlyMin = if (!isOpen && lastOut != null && lastOut.isBefore(shiftEndInstant.minusSeconds(60))) 
                       Duration.between(lastOut, shiftEndInstant).toMinutes() else 0L

        // Break Penalty: Excess break time beyond threshold
        val breakPenalty = if (breakMin > breakThresholdMinutes) breakMin - breakThresholdMinutes else 0L

        return AttendanceResult(
            date = date,
            totalWorkDurationMinutes = displayWorkMin,
            insideShiftDurationMinutes = insideShiftMin,
            outsideShiftDurationMinutes = validOvertimeMin,
            lateArrivalMinutes = lateMin,
            earlyLeaveMinutes = earlyMin,
            overtimeMinutes = validOvertimeMin,
            breakDurationMinutes = breakMin,
            breakPenaltyMinutes = breakPenalty,
            status = calculateStatus(displayWorkMin, lateMin, earlyMin, insideShiftMin, isOpen, isLive),
            isOpenPunch = isOpen,
            punches = sortedPunches
        )
    }

    private fun calculateStatus(netWorkMin: Long, lateMin: Long, earlyMin: Long, insideMin: Long, isOpen: Boolean, isLive: Boolean): String {
        return when {
            isOpen && isLive -> "PRESENT"
            isOpen && !isLive -> "MISSING_PUNCH"
            netWorkMin < 30 -> "ABSENT"
            insideMin < 240 -> "HALF_DAY"
            lateMin > 30 || earlyMin > 30 -> "LATE_OR_EARLY"
            else -> "PRESENT"
        }
    }
}
