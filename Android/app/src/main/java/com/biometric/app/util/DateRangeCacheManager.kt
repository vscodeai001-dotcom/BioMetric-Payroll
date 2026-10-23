package com.biometric.app.util

import android.content.Context

/**
 * Registry to track historical date ranges that have been fetched from cloud
 * and persisted locally into Room / SQLite / local disk storage.
 *
 * Prevents "storm bandwidth" (excessive multi-megabyte downloads) on Firebase Spark plan:
 * 1. The first time a user filters for a date range (e.g., 7 days, 15 days, 30 days) and searches,
 *    the range is fetched once and saved locally.
 * 2. On all subsequent searches or filter changes, data is served directly from local storage
 *    with 0 KB network requests.
 * 3. Realtime delta additions/modifications continue to arrive lightweight via ChildEventListener.
 */
object DateRangeCacheManager {
    private const val PREFS_NAME = "date_range_cache_registry"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Checks if [startDate]..[endDate] (in ISO format yyyy-MM-dd) is already covered
     * by a previously fetched date range for [table].
     */
    fun isRangeCached(context: Context, table: String, startDate: String, endDate: String): Boolean {
        if (startDate.isBlank() || endDate.isBlank()) return false
        val prefs = getPrefs(context)
        val ranges = prefs.getStringSet("${table}_cached_ranges", emptySet()) ?: emptySet()
        for (range in ranges) {
            val parts = range.split("..")
            if (parts.size == 2) {
                val rStart = parts[0]
                val rEnd = parts[1]
                if (startDate >= rStart && endDate <= rEnd) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Records that [startDate]..[endDate] has been fetched from cloud and saved locally.
     */
    fun markRangeCached(context: Context, table: String, startDate: String, endDate: String) {
        if (startDate.isBlank() || endDate.isBlank()) return
        val prefs = getPrefs(context)
        val current = prefs.getStringSet("${table}_cached_ranges", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add("$startDate..$endDate")
        prefs.edit().putStringSet("${table}_cached_ranges", current).apply()
    }

    /**
     * Checks if a GPS tracking history epoch range is cached for a given employee.
     */
    fun isTrackingHistoryCached(context: Context, employeeId: Int, startEpoch: Long, endEpoch: Long): Boolean {
        if (employeeId <= 0 || startEpoch <= 0 || endEpoch <= 0) return false
        val prefs = getPrefs(context)
        val ranges = prefs.getStringSet("tracking_${employeeId}_ranges", emptySet()) ?: emptySet()
        for (range in ranges) {
            val parts = range.split("..")
            if (parts.size == 2) {
                val rStart = parts[0].toLongOrNull() ?: continue
                val rEnd = parts[1].toLongOrNull() ?: continue
                if (startEpoch >= rStart && endEpoch <= rEnd) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Records that GPS tracking points in [startEpoch]..[endEpoch] have been saved locally.
     */
    fun markTrackingHistoryCached(context: Context, employeeId: Int, startEpoch: Long, endEpoch: Long) {
        if (employeeId <= 0 || startEpoch <= 0 || endEpoch <= 0) return
        val prefs = getPrefs(context)
        val current = prefs.getStringSet("tracking_${employeeId}_ranges", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add("$startEpoch..$endEpoch")
        prefs.edit().putStringSet("tracking_${employeeId}_ranges", current).apply()
    }

    /**
     * Clear registry when a remote wipe event occurs.
     */
    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
