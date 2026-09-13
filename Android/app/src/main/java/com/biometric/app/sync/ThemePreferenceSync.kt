package com.biometric.app.sync

import android.content.Context
import com.biometric.app.api.MobileApiService
import com.biometric.app.api.ThemePreferenceRequest
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.util.ThemeManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemePreferenceSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: MobileApiService,
    private val sessionStore: MobileSessionStore
) {
    suspend fun refreshFromServer(): Boolean = withContext(Dispatchers.IO) {
        val token = sessionStore.token() ?: return@withContext false
        val response = runCatching {
            api.getTheme("Bearer $token")
        }.getOrNull() ?: return@withContext false

        if (!response.isSuccessful) return@withContext false

        val theme = response.body()?.theme ?: "light"
        ThemeManager.setTheme(context, theme, sessionStore.userThemeKey())
        true
    }

    suspend fun persist(theme: String): Boolean = withContext(Dispatchers.IO) {
        val token = sessionStore.token() ?: return@withContext false
        val response = runCatching {
            api.saveTheme(
                "Bearer $token",
                ThemePreferenceRequest(theme)
            )
        }.getOrNull() ?: return@withContext false

        if (!response.isSuccessful) return@withContext false

        val serverTheme = response.body()?.theme ?: theme
        ThemeManager.setTheme(context, serverTheme, sessionStore.userThemeKey())
        true
    }
}
