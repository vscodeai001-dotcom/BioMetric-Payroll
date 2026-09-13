package com.biometric.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

object ThemeManager {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    fun applyTheme(context: Context) {
        val mode = getThemeMode(context)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun getThemeMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_NO)
    }

    private var lastToggleTime = 0L

    fun toggleTheme(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastToggleTime < 1000) return // Prevent rapid toggling
        lastToggleTime = now

        val currentMode = getThemeMode(context)
        val newMode = if (currentMode == AppCompatDelegate.MODE_NIGHT_YES) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_THEME_MODE, newMode)
        }
        AppCompatDelegate.setDefaultNightMode(newMode)
    }
}