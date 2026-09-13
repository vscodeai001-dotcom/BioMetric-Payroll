package com.biometric.app.sync

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central UI invalidation bridge. It never recreates an Activity and never
 * changes business logic. Visible screens opt into the existing no-argument
 * load/refresh methods they already own. The reflection fallback is deliberately
 * limited to known refresh-style method names so forms and navigation are not
 * disturbed.
 */
@Singleton
class RealtimeScreenRegistry @Inject constructor() {
    private val main = Handler(Looper.getMainLooper())
    private val activities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    fun register(activity: Activity) {
        synchronized(activities) { activities.add(activity) }
    }

    fun unregister(activity: Activity) {
        synchronized(activities) { activities.remove(activity) }
    }

    fun notifyVisibleScreens() {
        val snapshot = synchronized(activities) { activities.toList() }
        main.post {
            snapshot.forEach { activity ->
                if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) return@forEach
                refreshExistingLoader(activity)
            }
        }
    }

    private fun refreshExistingLoader(activity: Activity) {
        // MainActivity already owns a dedicated realtime collector. Avoid a
        // second refresh path there.
        if (activity.javaClass.simpleName == "MainActivity") return

        val names = listOf(
            "refreshData", "loadHistory", "loadIssues", "loadPreview",
            "loadProfile", "loadAttendance", "loadLeaves", "loadShifts", "load"
        )

        for (name in names) {
            val method = findNoArgMethod(activity.javaClass, name) ?: continue
            try {
                method.isAccessible = true
                method.invoke(activity)
                Log.d("RealtimeScreenRegistry", "Realtime refresh: ${activity.javaClass.simpleName}.$name()")
                return
            } catch (t: Throwable) {
                Log.w("RealtimeScreenRegistry", "Could not invoke $name on ${activity.javaClass.simpleName}", t)
                return
            }
        }
    }

    private fun findNoArgMethod(type: Class<*>, name: String): Method? {
        var current: Class<*>? = type
        while (current != null && current != Activity::class.java) {
            runCatching { current.getDeclaredMethod(name) }.getOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }
}
