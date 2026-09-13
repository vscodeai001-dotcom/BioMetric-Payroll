package com.biometric.app.ai

import java.util.*

object NaturalDateParser {

    fun parse(query: String): Triple<String, Long, Long>? {
        val q = query.lowercase().trim()
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis

        // Specific Day of Month (e.g. May 1)
        val monthRegex = "(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)"
        Regex("$monthRegex\\s*(\\d{1,2})").find(q)?.let { match ->
            val monthName = match.groupValues[1]
            val day = match.groupValues[2].toInt()
            val monthIndex = getMonthIndex(monthName)
            val start = Calendar.getInstance().apply {
                set(Calendar.MONTH, monthIndex)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
            }
            return Triple("${monthName.uppercase()} $day", start.timeInMillis, end.timeInMillis)
        }

        // Last X Days
        Regex("last\\s*(\\d+)\\s*days?").find(q)?.let { match ->
            val count = match.groupValues[1].toInt()
            val start = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -(count - 1))
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return Triple("Last $count Days", start.timeInMillis, now)
        }

        // Days Ago
        Regex("(\\d+)\\s*days?\\s*ago").find(q)?.let { match ->
            val count = match.groupValues[1].toInt()
            val start = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -count)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
            }
            return Triple("$count Days Ago", start.timeInMillis, end.timeInMillis)
        }

        // Sundays in Month (e.g. May Sundays)
        if (q.contains("sundays") && Regex(monthRegex).containsMatchIn(q)) {
            val monthName = Regex(monthRegex).find(q)?.value ?: ""
            val monthIndex = getMonthIndex(monthName)
            val start = Calendar.getInstance().apply {
                set(Calendar.MONTH, monthIndex)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
            }
            return Triple("${monthName.uppercase()} Sundays", start.timeInMillis, end.timeInMillis)
        }

        // Last X Months
        Regex("last\\s*(\\d+)\\s*months?").find(q)?.let { match ->
            val count = match.groupValues[1].toInt()
            val start = Calendar.getInstance().apply {
                add(Calendar.MONTH, -count)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return Triple("Last $count Months", start.timeInMillis, now)
        }

        // Specific Days of Week (e.g. Tuesdays in last 3 months)
        val dayOfWeekRegex = "(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun)"
        if (Regex(dayOfWeekRegex).containsMatchIn(q)) {
            val dayName = Regex(dayOfWeekRegex).find(q)?.value ?: ""
            // Check if it specifies a month or range
            val countMatch = Regex("last\\s*(\\d+)\\s*months?").find(q)
            val start = if (countMatch != null) {
                val count = countMatch.groupValues[1].toInt()
                Calendar.getInstance().apply { add(Calendar.MONTH, -count); set(Calendar.DAY_OF_MONTH, 1) }
            } else {
                Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) } // Default this month
            }
            start.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            
            return Triple("${dayName.uppercase()}S", start.timeInMillis, now)
        }

        // Relative Days of Week (e.g. last Sunday, this Tuesday)
        Regex("(this|last|previous)\\s*$dayOfWeekRegex").find(q)?.let { match ->
            val relative = match.groupValues[1]
            val dayName = match.groupValues[2]
            val dayOfWeek = getDayOfWeekInt(dayName)
            val cal = Calendar.getInstance()
            
            if (relative == "last" || relative == "previous") {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                while (cal[Calendar.DAY_OF_WEEK] != dayOfWeek) {
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                }
            } else { // this
                while (cal[Calendar.DAY_OF_WEEK] != dayOfWeek) {
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                }
            }
            
            val start = (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
            }
            return Triple("${relative.uppercase()} ${dayName.uppercase()}", start.timeInMillis, end.timeInMillis)
        }

        if (q.contains("yesterday")) {
            val start = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
            }
            return Triple("Yesterday", start.timeInMillis, end.timeInMillis)
        }

        if (q.contains("today")) {
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return Triple("Today", start.timeInMillis, now)
        }

        if (q.contains("last week")) {
            val start = Calendar.getInstance().apply {
                add(Calendar.WEEK_OF_YEAR, -1)
                set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply {
                add(Calendar.DAY_OF_WEEK, 6)
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
            }
            return Triple("Last Week", start.timeInMillis, end.timeInMillis)
        }

        if (q.contains("this week")) {
            val start = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return Triple("This Week", start.timeInMillis, now)
        }

        if (q.contains("last month") || q.contains("previous month")) {
            val start = Calendar.getInstance().apply {
                add(Calendar.MONTH, -1)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
            }
            return Triple("Last Month", start.timeInMillis, end.timeInMillis)
        }

        if (q.contains("this month")) {
            val start = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return Triple("This Month", start.timeInMillis, now)
        }

        // Generic Month
        months.forEachIndexed { index, name ->
            if (q.contains(name) || q.contains(name.take(3))) {
                val start = Calendar.getInstance().apply {
                    set(Calendar.MONTH, index)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val end = (start.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                }
                return Triple(name.uppercase(), start.timeInMillis, end.timeInMillis)
            }
        }

        return null
    }

    private val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    private fun getDayOfWeekInt(name: String): Int {
        return when (name.lowercase().take(3)) {
            "sun" -> Calendar.SUNDAY
            "mon" -> Calendar.MONDAY
            "tue" -> Calendar.TUESDAY
            "wed" -> Calendar.WEDNESDAY
            "thu" -> Calendar.THURSDAY
            "fri" -> Calendar.FRIDAY
            "sat" -> Calendar.SATURDAY
            else -> Calendar.SUNDAY
        }
    }

    private fun getMonthIndex(name: String): Int {
        val shortMonths = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        val idx = months.indexOf(name)
        if (idx != -1) return idx
        return shortMonths.indexOf(name.take(3)).coerceAtLeast(0)
    }
}
