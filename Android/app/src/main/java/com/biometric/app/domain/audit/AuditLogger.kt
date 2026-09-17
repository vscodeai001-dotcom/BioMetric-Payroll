package com.biometric.app.domain.audit

import com.biometric.app.data.entity.AuditLog
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.sync.FirebaseSyncManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canonical Android audit writer. Audit records belong to the owner's RTDB
 * audit_logs projection, not the unrelated Firestore auditLogs collection.
 * Credentials, bearer tokens and other secrets are deliberately redacted.
 */
@Singleton
class AuditLogger @Inject constructor(
    private val firebaseSync: FirebaseSyncManager,
    private val sessionStore: MobileSessionStore
) {
    private val auth = FirebaseAuth.getInstance()

    suspend fun logAction(
        shopId: String?,
        action: String,
        module: String,
        oldValue: String? = null,
        newValue: String? = null,
        targetId: String? = null
    ): Boolean {
        val user = auth.currentUser ?: return false
        val ownerUid = firebaseSync.getOwnerUid().orEmpty().ifBlank {
            runCatching { user.getIdToken(false).await().claims["owner_uid"]?.toString().orEmpty() }.getOrDefault("")
        }
        if (ownerUid.isBlank()) return false

        val role = runCatching {
            user.getIdToken(false).await().claims["role"]?.toString().orEmpty()
        }.getOrDefault("")
        if (role !in setOf("Admin", "SuperAdmin", "ADMIN", "SUPER_ADMIN", "Employee", "STAFF", "Staff")) return false

        val log = AuditLog(
            logId = UUID.randomUUID().toString(),
            shopId = shopId?.takeIf { it.isNotBlank() } ?: "GLOBAL",
            action = sanitize(action).orEmpty(),
            module = sanitize(module).orEmpty(),
            oldValue = sanitizePayload(oldValue),
            newValue = sanitizePayload(newValue),
            userDisplayName = sanitize(sessionStore.employeeName().ifBlank { user.displayName ?: user.phoneNumber ?: "System" }),
            userId = user.uid,
            actorRole = role,
            ownerUid = ownerUid,
            targetId = sanitize(targetId),
            timestamp = System.currentTimeMillis()
        )
        return runCatching { firebaseSync.pushAuditLog(log); true }.getOrDefault(false)
    }

    private fun sanitize(value: String?): String? = value
        ?.replace(Regex("(?i)(password|passwd|token|authorization|refreshToken|accessToken)\\s*[:=]\\s*[^,;\\s]+"), "\$1=[REDACTED]")
        ?.take(MAX_FIELD)

    private fun sanitizePayload(value: String?): String? {
        if (value == null) return null
        var result = value
        SECRET_KEYS.forEach { key ->
            result = result.replace(Regex("(?i)(\\\"?$key\\\"?\\s*[:=]\\s*\\\"?)[^,;\\\"}\\s]+"), "\$1[REDACTED]")
        }
        return result.take(MAX_FIELD)
    }

    companion object {
        private const val MAX_FIELD = 4000
        private val SECRET_KEYS = listOf("password", "passwd", "token", "authorization", "refreshToken", "accessToken", "apiKey", "secret")
    }
}
