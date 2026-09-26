package com.biometric.app.sync

import android.util.Log
import com.biometric.app.data.MobileSessionStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GetTokenResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates all Firebase token retrieval and silent session restoration.
 * Uses a Mutex so concurrent callers never flood Google Identity Toolkit with
 * parallel refresh requests that trigger refresh-token rotation and invalidation.
 */
@Singleton
class FirebaseAuthTokenManager @Inject constructor(
    private val sessionStore: MobileSessionStore
) {
    private val refreshMutex = Mutex()
    private var lastRefreshTimeMs = 0L

    suspend fun getValidToken(forceRefresh: Boolean = false): String? = refreshMutex.withLock {
        val auth = FirebaseAuth.getInstance()
        var user = auth.currentUser

        // 1. If Firebase Auth currentUser is null or signed out, attempt silent re-auth
        if (user == null && sessionStore.isLoggedIn()) {
            val (savedEmail, savedPass) = sessionStore.getSavedAuthCredentials()
            if (!savedEmail.isNullOrBlank() && !savedPass.isNullOrBlank()) {
                Log.i("FirebaseAuthTokenMgr", "Firebase currentUser is null; attempting silent background re-auth...")
                runCatching {
                    auth.signInWithEmailAndPassword(savedEmail, savedPass).await()
                    user = auth.currentUser
                    Log.i("FirebaseAuthTokenMgr", "Silent re-auth succeeded for $savedEmail")
                }.onFailure {
                    Log.w("FirebaseAuthTokenMgr", "Silent re-auth failed", it)
                }
            }
        }

        if (user == null) {
            return@withLock sessionStore.token()
        }

        val now = System.currentTimeMillis()
        // 2. Debounce: if refreshed within last 20 seconds, reuse cached token
        if (!forceRefresh && (now - lastRefreshTimeMs < 20_000L)) {
            return@withLock sessionStore.token()
        }

        // 3. Obtain valid token without triggering token rotation collision
        return@withLock runCatching {
            val cachedResult: GetTokenResult = user.getIdToken(false).await()
            val nowSec = now / 1000L
            val isExpiringSoon = cachedResult.expirationTimestamp <= (nowSec + 120L)

            val finalResult: GetTokenResult = if (forceRefresh || isExpiringSoon) {
                Log.i("FirebaseAuthTokenMgr", "Token expiring or refresh forced; requesting token (force=$forceRefresh, expiringSoon=$isExpiringSoon)")
                user.getIdToken(true).await()
            } else {
                cachedResult
            }

            finalResult.token?.let { freshToken ->
                lastRefreshTimeMs = System.currentTimeMillis()
                sessionStore.saveLogin(
                    token = freshToken,
                    employeeId = sessionStore.employeeId(),
                    name = sessionStore.employeeName(),
                    email = user.email.orEmpty(),
                    firebaseOwnerUid = sessionStore.firebaseOwnerUid()
                )
                freshToken
            } ?: sessionStore.token()
        }.getOrElse { error ->
            Log.w("FirebaseAuthTokenMgr", "Token fetch failed: ${error.message}", error)
            // If the token was invalidated or user signed out by SDK, recover via silent re-auth
            if (error is com.google.firebase.auth.FirebaseAuthInvalidUserException || auth.currentUser == null) {
                val (savedEmail, savedPass) = sessionStore.getSavedAuthCredentials()
                if (!savedEmail.isNullOrBlank() && !savedPass.isNullOrBlank()) {
                    Log.i("FirebaseAuthTokenMgr", "Invalid credential detected. Auto-recovering session via credentials...")
                    runCatching {
                        auth.signInWithEmailAndPassword(savedEmail, savedPass).await()
                        val newUser = auth.currentUser
                        val refreshed = newUser?.getIdToken(false)?.await()?.token
                        if (!refreshed.isNullOrBlank()) {
                            lastRefreshTimeMs = System.currentTimeMillis()
                            sessionStore.saveLogin(
                                token = refreshed,
                                employeeId = sessionStore.employeeId(),
                                name = sessionStore.employeeName(),
                                email = newUser.email.orEmpty(),
                                firebaseOwnerUid = sessionStore.firebaseOwnerUid()
                            )
                            Log.i("FirebaseAuthTokenMgr", "Session auto-recovered successfully!")
                            return@withLock refreshed
                        }
                    }.onFailure { Log.e("FirebaseAuthTokenMgr", "Auto-recovery failed", it) }
                }
            }
            sessionStore.token()
        }
    }
}
