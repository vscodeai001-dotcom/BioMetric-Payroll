package com.biometric.app.domain.location

import android.content.Context
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.dao.LocalShiftScheduleDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves whether background tracking is currently allowed by the employee's
 * tracking mode and assigned shift schedule.
 *
 * Default remains 24/7 so existing deployments are not silently changed.
 * SHIFT mode becomes active only when explicitly configured through the local
 * tracking_mode preference by the existing settings flow.
 */
@Singleton
class TrackingWindowResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionStore: MobileSessionStore,
    private val shiftScheduleDao: LocalShiftScheduleDao
) {
    data class Window(
        val allowed: Boolean,
        val mode: String,
        val start: LocalDateTime? = null,
        val end: LocalDateTime? = null,
        val source: String = "NONE"
    )

    fun observeShiftChanges(): Flow<List<com.biometric.app.data.entity.LocalShiftSchedule>> {
        val employeeId = sessionStore.employeeId()
        if (employeeId <= 0) return kotlinx.coroutines.flow.flowOf(emptyList())
        val today = LocalDate.now(ZoneId.systemDefault())
        return shiftScheduleDao.observeForEmployeeBetween(employeeId, today.minusDays(1).toString(), today.toString())
    }

    suspend fun resolve(now: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault())): Window {
        val mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TRACKING_MODE, MODE_24_7)
            ?.trim()
            ?.uppercase()
            ?: MODE_24_7

        return when (mode) {
            MODE_SHIFT -> resolveShift(now)
            MODE_CUSTOM -> resolveCustom(now)
            else -> Window(true, MODE_24_7, source = "24/7")
        }
    }

    private suspend fun resolveShift(now: LocalDateTime): Window {
        val employeeId = sessionStore.employeeId()
        if (employeeId <= 0) return Window(false, MODE_SHIFT, source = "NO_EMPLOYEE")

        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val schedules = shiftScheduleDao.getForEmployeeBetween(
            employeeId,
            yesterday.toString(),
            today.toString()
        )

        val candidates = schedules.flatMap { schedule ->
            val date = runCatching { LocalDate.parse(schedule.shiftDate) }.getOrNull() ?: return@flatMap emptyList()
            val start = parseTime(schedule.startTime) ?: return@flatMap emptyList()
            val end = parseTime(schedule.endTime) ?: return@flatMap emptyList()
            val startDateTime = LocalDateTime.of(date, start)
            val endDateTime = LocalDateTime.of(
                if (!end.isAfter(start)) date.plusDays(1) else date,
                end
            )
            listOf(Window(true, MODE_SHIFT, startDateTime, endDateTime, "SHIFT_SCHEDULE"))
        }

        val active = candidates.firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }
        if (active != null) return active

        val next = candidates.filter { it.start != null && it.start.isAfter(now) }.minByOrNull { it.start!! }
        return Window(false, MODE_SHIFT, next?.start, next?.end, if (candidates.isEmpty()) "NO_SHIFT" else "OUTSIDE_SHIFT")
    }

    private fun resolveCustom(now: LocalDateTime): Window {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val startText = prefs.getString(KEY_CUSTOM_START, null)
        val endText = prefs.getString(KEY_CUSTOM_END, null)
        val start = parseTime(startText) ?: return Window(false, MODE_CUSTOM, source = "INVALID_CUSTOM")
        val end = parseTime(endText) ?: return Window(false, MODE_CUSTOM, source = "INVALID_CUSTOM")

        var startDate = now.toLocalDate()
        var endDate = startDate
        if (!end.isAfter(start) && now.toLocalTime().isBefore(end)) startDate = startDate.minusDays(1)
        if (!end.isAfter(start)) endDate = startDate.plusDays(1)
        val startDateTime = LocalDateTime.of(startDate, start)
        val endDateTime = LocalDateTime.of(endDate, end)
        val allowed = !now.isBefore(startDateTime) && now.isBefore(endDateTime)
        return Window(allowed, MODE_CUSTOM, startDateTime, endDateTime, "CUSTOM")
    }

    private fun parseTime(value: String?): LocalTime? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        return runCatching {
            LocalTime.parse(if (text.length == 5) "$text:00" else text)
        }.getOrNull()
    }

    companion object {
        const val PREFS = "tracking_prefs"
        const val KEY_TRACKING_MODE = "tracking_mode"
        const val KEY_CUSTOM_START = "tracking_custom_start"
        const val KEY_CUSTOM_END = "tracking_custom_end"
        const val MODE_SHIFT = "SHIFT"
        const val MODE_24_7 = "24/7"
        const val MODE_CUSTOM = "CUSTOM"
    }
}
