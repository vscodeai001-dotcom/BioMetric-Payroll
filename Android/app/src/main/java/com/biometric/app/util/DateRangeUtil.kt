package com.biometric.app.util

import java.text.SimpleDateFormat
import java.util.*

object DateRangeUtil {

    /**
     * returns (Start, End) timestamps for a given period.
     * @param isMatchingDays If true, the end timestamp will be relative to the provided [date] 
     *                       (e.g. for "Monthly", it returns 1st to 11th if date is 11th).
     *                       If false, it returns the full period (1st to 31st).
     */
    fun getRangeForPeriod(period: String, date: Long, isMatchingDays: Boolean = false, endDate: Long? = null): Pair<Long, Long> {
        val normalizedPeriod = period.lowercase().replace("-", " ").trim()
        val start = Calendar.getInstance().apply { timeInMillis = date }
        
        // ONLY use endDate if we are in Custom mode. For all other modes, end is derived from start date.
        val actualEndTs = if (normalizedPeriod == "custom") (endDate ?: date) else date
        val end = Calendar.getInstance().apply { timeInMillis = actualEndTs }
        
        val today = Calendar.getInstance()

        when (normalizedPeriod) {
            "daily", "day" -> { }
            "up to date", "uptodate" -> {
                // Start from the 1st of the month
                start[Calendar.DAY_OF_MONTH] = 1
                // End date stays at the adjusted 'date' (allows daily navigation)
            }
            "weekly", "week" -> {
                start[Calendar.DAY_OF_WEEK] = start.firstDayOfWeek
                if (isMatchingDays) {
                    val daysIntoWeek = ((today[Calendar.DAY_OF_WEEK] - today.firstDayOfWeek + 7) % 7)
                    end.timeInMillis = start.timeInMillis
                    end.add(Calendar.DAY_OF_YEAR, daysIntoWeek)
                } else {
                    end[Calendar.DAY_OF_WEEK] = start.firstDayOfWeek + 6
                }
            }
            "monthly", "month" -> {
                start[Calendar.DAY_OF_MONTH] = 1
                if (isMatchingDays) {
                    end[Calendar.DAY_OF_MONTH] = today[Calendar.DAY_OF_MONTH].coerceAtMost(end.getActualMaximum(Calendar.DAY_OF_MONTH))
                } else {
                    end[Calendar.DAY_OF_MONTH] = end.getActualMaximum(Calendar.DAY_OF_MONTH)
                }
            }
            "quarterly", "quarter" -> {
                val quarter = start[Calendar.MONTH] / 3
                start[Calendar.MONTH] = quarter * 3
                start[Calendar.DAY_OF_MONTH] = 1
                if (isMatchingDays) {
                    val monthsIntoQuarter = (today[Calendar.MONTH] % 3)
                    end[Calendar.MONTH] = (quarter * 3) + monthsIntoQuarter
                    end[Calendar.DAY_OF_MONTH] = today[Calendar.DAY_OF_MONTH].coerceAtMost(end.getActualMaximum(Calendar.DAY_OF_MONTH))
                } else {
                    end[Calendar.MONTH] = (quarter * 3) + 2
                    end[Calendar.DAY_OF_MONTH] = end.getActualMaximum(Calendar.DAY_OF_MONTH)
                }
            }
            "half yearly", "half year" -> {
                val half = if (start[Calendar.MONTH] < 6) 0 else 6
                start[Calendar.MONTH] = half
                start[Calendar.DAY_OF_MONTH] = 1
                if (isMatchingDays) {
                    val monthsIntoHalf = (today[Calendar.MONTH] % 6)
                    end[Calendar.MONTH] = half + monthsIntoHalf
                    end[Calendar.DAY_OF_MONTH] = today[Calendar.DAY_OF_MONTH].coerceAtMost(end.getActualMaximum(Calendar.DAY_OF_MONTH))
                } else {
                    end[Calendar.MONTH] = half + 5
                    end[Calendar.DAY_OF_MONTH] = end.getActualMaximum(Calendar.DAY_OF_MONTH)
                }
            }
            "annually", "annual" -> {
                start[Calendar.DAY_OF_YEAR] = 1
                if (isMatchingDays) {
                    end[Calendar.MONTH] = today[Calendar.MONTH]
                    end[Calendar.DAY_OF_MONTH] = today[Calendar.DAY_OF_MONTH].coerceAtMost(end.getActualMaximum(Calendar.DAY_OF_MONTH))
                } else {
                    end[Calendar.MONTH] = 11
                    end[Calendar.DAY_OF_MONTH] = 31
                }
            }
            "custom" -> { }
        }

        start[Calendar.HOUR_OF_DAY] = 0; start[Calendar.MINUTE] = 0; start[Calendar.SECOND] = 0; start[Calendar.MILLISECOND] = 0
        end[Calendar.HOUR_OF_DAY] = 23; end[Calendar.MINUTE] = 59; end[Calendar.SECOND] = 59; end[Calendar.MILLISECOND] = 999

        return start.timeInMillis to end.timeInMillis
    }

    /**
     * Returns (Start, End) timestamps for the REMAINING part of a past period.
     * Used for forecasting based on historical performance of specific days.
     */
    fun getRemainingRangeForPeriod(period: String, date: Long): Pair<Long, Long> {
        val start = Calendar.getInstance().apply { timeInMillis = date }
        val end = Calendar.getInstance().apply { timeInMillis = date }
        
        // Move to the day AFTER the current matching day
        start.add(Calendar.DAY_OF_MONTH, 1)
        start[Calendar.HOUR_OF_DAY] = 0; start[Calendar.MINUTE] = 0; start[Calendar.SECOND] = 0; start[Calendar.MILLISECOND] = 0
        
        val normalizedPeriod = period.lowercase().replace("-", " ").trim()
        when (normalizedPeriod) {
            "up to date", "monthly", "month" -> {
                end[Calendar.DAY_OF_MONTH] = end.getActualMaximum(Calendar.DAY_OF_MONTH)
            }
            "weekly", "week" -> {
                end[Calendar.DAY_OF_WEEK] = end.firstDayOfWeek + 6
            }
            else -> {
                // For daily or others, remaining is just the rest of the day (usually not used for forecast)
            }
        }
        
        end[Calendar.HOUR_OF_DAY] = 23; end[Calendar.MINUTE] = 59; end[Calendar.SECOND] = 59; end[Calendar.MILLISECOND] = 999
        return if (start.timeInMillis > end.timeInMillis) (0L to 0L) else (start.timeInMillis to end.timeInMillis)
    }

    fun adjustDate(period: String, calendar: Calendar, amount: Int) {
        val normalizedPeriod = period.lowercase().replace("-", " ").trim()
        when {
            normalizedPeriod == "daily" || normalizedPeriod == "day" || normalizedPeriod == "up to date" || normalizedPeriod == "uptodate" -> calendar.add(Calendar.DAY_OF_MONTH, amount)
            normalizedPeriod == "monthly" || normalizedPeriod == "month" -> calendar.add(Calendar.MONTH, amount)
            normalizedPeriod == "weekly" || normalizedPeriod == "week" -> calendar.add(Calendar.WEEK_OF_YEAR, amount)
            normalizedPeriod == "quarterly" || normalizedPeriod == "quarter" -> calendar.add(Calendar.MONTH, amount * 3)
            normalizedPeriod == "half yearly" || normalizedPeriod == "half year" -> calendar.add(Calendar.MONTH, amount * 6)
            normalizedPeriod == "annually" || normalizedPeriod == "annual" -> calendar.add(Calendar.YEAR, amount)
            else -> calendar.add(Calendar.DAY_OF_MONTH, amount)
        }
    }

    fun getFormattedRangeLabel(period: String, dateMillis: Long, endDate: Long? = null): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val normalizedPeriod = period.lowercase().replace("-", " ").trim()
        val year = cal[Calendar.YEAR]

        return when (normalizedPeriod) {
            "daily", "day" -> {
                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(cal.time)
            }
            "up to date", "uptodate" -> {
                val sdf = SimpleDateFormat("dd MMM, yyyy", Locale.getDefault())
                "1st to ${sdf.format(cal.time)}"
            }
            "weekly", "week" -> {
                val weekStart = cal.clone() as Calendar
                weekStart[Calendar.DAY_OF_WEEK] = weekStart.firstDayOfWeek
                val weekEnd = weekStart.clone() as Calendar
                weekEnd.add(Calendar.DAY_OF_WEEK, 6)
                val sdf = SimpleDateFormat("dd MMM", Locale.getDefault())
                "${sdf.format(weekStart.time)} - ${sdf.format(weekEnd.time)}, $year"
            }
            "monthly", "month" -> {
                SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
            }
            "quarterly", "quarter" -> {
                val label = when (cal[Calendar.MONTH] / 3) {
                    0 -> "Jan to Mar"
                    1 -> "Apr to Jun"
                    2 -> "Jul to Sep"
                    else -> "Oct to Dec"
                }
                "$label $year"
            }
            "half yearly", "half year" -> {
                val half = cal[Calendar.MONTH] / 6
                val label = if (half == 0) "Jan to Jun" else "Jul to Dec"
                "$label $year"
            }
            "annually", "annual" -> {
                year.toString()
            }
            "custom" -> {
                val sdf = SimpleDateFormat("dd MMM yy", Locale.getDefault())
                val endStr = if (endDate != null) sdf.format(Date(endDate)) else "..."
                "${sdf.format(cal.time)} - $endStr"
            }
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(cal.time)
        }
    }
    
    /**
     * Shifts the calendar back by exactly one full logical period.
     */
    fun shiftPeriod(period: String, calendar: Calendar, offset: Int) {
        val normalizedPeriod = period.lowercase().replace("-", " ").trim()
        when {
            normalizedPeriod == "up to date" || normalizedPeriod == "monthly" || normalizedPeriod == "month" -> calendar.add(Calendar.MONTH, offset)
            normalizedPeriod == "weekly" || normalizedPeriod == "week" -> calendar.add(Calendar.WEEK_OF_YEAR, offset)
            normalizedPeriod == "quarterly" || normalizedPeriod == "quarter" -> calendar.add(Calendar.MONTH, offset * 3)
            normalizedPeriod == "half yearly" || normalizedPeriod == "half year" -> calendar.add(Calendar.MONTH, offset * 6)
            normalizedPeriod == "annually" || normalizedPeriod == "annual" -> calendar.add(Calendar.YEAR, offset)
            normalizedPeriod == "custom" -> { }
            else -> calendar.add(Calendar.DAY_OF_MONTH, offset)
        }
    }

    fun getStartOfDay(timestamp: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun getEndOfDay(timestamp: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }
}
