package com.biometric.app.sync

import android.content.Context
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.util.ThemeManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1016: Firebase is the cross-device theme source of truth.
 * SharedPreferences remains only the last-known local cache so the selected
 * theme can be applied immediately while offline or during app startup.
 */
@Singleton
class ThemePreferenceSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val firebaseSync: FirebaseSyncManager,
    private val sessionStore: MobileSessionStore
) {
    private fun uid(): String? =
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid

    suspend fun refreshFromServer(): Boolean = withContext(Dispatchers.IO) {
        val userUid = uid() ?: return@withContext false
        val theme = firebaseSync.getUserTheme(userUid) ?: return@withContext false
        ThemeManager.setTheme(context, theme, sessionStore.userThemeKey())
        true
    }

    suspend fun persist(theme: String): Boolean = withContext(Dispatchers.IO) {
        val userUid = uid() ?: return@withContext false
        val normalized = if (theme.equals("dark", true)) "dark" else "light"

        // Apply locally first so the UI remains responsive even if the network
        // is temporarily unavailable. Firebase SDK offline persistence queues
        // the write for later synchronization.
        ThemeManager.setTheme(context, normalized, sessionStore.userThemeKey())
        firebaseSync.setUserTheme(userUid, normalized)
    }
}
