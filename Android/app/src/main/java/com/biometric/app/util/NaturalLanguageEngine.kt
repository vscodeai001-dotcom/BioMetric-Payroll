package com.biometric.app.util

import java.util.*

object NaturalLanguageEngine {

    private val STOP_WORDS = setOf(
        "how", "much", "did", "i", "on", "for", "the", "of", "a", "an", "is", "was", "show", "me", "find", "get", "what", "total", "any", "at", "were", "my", "list", "all", "about", "your", "can", "tell", "please", "thanks", "thank", "you", "give", "display", "amt", "amount", "record", "records",
    )

    private val TIME_KEYWORDS = setOf(
        "today", "yesterday", "week", "month", "last", "this", "days", "day", "ago", "period", "range", "from", "to", "between",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "mondays", "tuesdays", "wednesdays", "thursdays", "fridays", "saturdays", "sundays", "1st", "first", "last",
    )

    private val INTENT_KEYWORDS = setOf(
        "salary", "advance", "payment", "staff", "worker", "employee", "absent", "present", "attendance", "leave", "absentees", "workers", "employees", "health", "goal", "target",
    )

    fun parseGlobalTimeCommand(query: String): Triple<String, Long, Long>? {
        val q = query.lowercase().trim()
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        
        Regex("last\\s*(\\d+)\\s*days?").find(q)?.let { match ->
            val count = match.groupValues[1].toIntOrNull() ?: 1
            val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(count - 1)); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            return Triple("Last $count Days", start.timeInMillis, now)
        }

        Regex("(\\d+)\\s*days?\\s*ago").find(q)?.let { match ->
            val count = match.groupValues[1].toIntOrNull() ?: 1
            val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -count); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val end = (start.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }
            return Triple("$count Days Ago", start.timeInMillis, end.timeInMillis)
        }

        Regex("(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)\\s*(\\d{4})").find(q)?.let { match ->
            val monthName = match.groupValues[1]
            val year = match.groupValues[2].toIntOrNull() ?: cal[Calendar.YEAR]
            val monthIndex = getMonthIndex(monthName)
            val start = Calendar.getInstance().apply { set(Calendar.YEAR, year); set(Calendar.MONTH, monthIndex); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val end = (start.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH)); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }
            return Triple("${monthName.uppercase(Locale.getDefault())} $year", start.timeInMillis, end.timeInMillis)
        }

        if (q.contains("last month") || q.contains("previous month")) {
            cal.add(Calendar.MONTH, -1)
            val start = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val end = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH)); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }
            return Triple("Last Month", start.timeInMillis, end.timeInMillis)
        }
        
        if (q.contains("last week")) {
            cal.add(Calendar.WEEK_OF_YEAR, -1)
            val start = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_WEEK, firstDayOfWeek); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_WEEK, 6); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }
            return Triple("Last Week", start.timeInMillis, end.timeInMillis)
        }

        if (q.contains("yesterday")) {
            val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val end = (start.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }
            return Triple("Yesterday", start.timeInMillis, end.timeInMillis)
        }

        if (q.contains("today")) {
            val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            return Triple("Today", start.timeInMillis, now)
        }

        return null
    }

    private fun getMonthIndex(name: String): Int {
        val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        return months.indexOfFirst { name.startsWith(it) }.coerceAtLeast(0)
    }
}
