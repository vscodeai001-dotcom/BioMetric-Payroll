package com.biometric.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/**
 * Per-user presentation preference cache.
 *
 * The server-side user preference is the cross-device source of truth.
 * This local value is only the last-known cache used while the app starts
 * or when the device is temporarily offline.
 */
object ThemeManager {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_PREFIX = "theme_mode_"
    private const val DEFAULT_USER_KEY = "default"

    private var lastToggleTime = 0L

    private fun userKey(userKey: String?): String =
        userKey?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: DEFAULT_USER_KEY

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(userKey: String?): String = KEY_PREFIX + userKey(userKey)

    fun applyTheme(context: Context, userKey: String? = null) {
        val mode = prefs(context).getInt(key(userKey), AppCompatDelegate.MODE_NIGHT_NO)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    fun themeName(context: Context, userKey: String? = null): String =
        if (prefs(context).getInt(key(userKey), AppCompatDelegate.MODE_NIGHT_NO) ==
            AppCompatDelegate.MODE_NIGHT_YES) "dark" else "light"

    fun setTheme(context: Context, theme: String, userKey: String? = null) {
        val normalized = if (theme.equals("dark", true)) "dark" else "light"
        val mode = if (normalized == "dark")
            AppCompatDelegate.MODE_NIGHT_YES
        else
            AppCompatDelegate.MODE_NIGHT_NO

        prefs(context).edit { putInt(key(userKey), mode) }

        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    fun toggleTheme(context: Context, userKey: String? = null): String {
        val now = System.currentTimeMillis()
        if (now - lastToggleTime < 1000) return themeName(context, userKey)
        lastToggleTime = now

        val newTheme =
            if (themeName(context, userKey) == "dark") "light" else "dark"

        setTheme(context, newTheme, userKey)
        return newTheme
    }
}
