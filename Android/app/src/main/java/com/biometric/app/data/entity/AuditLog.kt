package com.biometric.app.data.entity

import androidx.annotation.Keep
import com.google.gson.Gson
import java.util.UUID

@Keep
data class AuditLog(
    var logId: String = UUID.randomUUID().toString(),
    var shopId: String = "",
    var action: String = "", // ADD, UPDATE, DELETE, RESTORE, VIEW, LOGIN_FAIL, AUTH_SESSION
    var module: String = "", // Staff, Attendance, Shop, Approvals, Tracking, etc.
    var oldValue: String? = null,
    var newValue: String? = null,
    var userDisplayName: String = "",
    var userId: String = "",
    var actorRole: String = "",
    var ownerUid: String = "",
    var targetId: String? = null,
    var timestamp: Long = System.currentTimeMillis()
) {
    // Secondary no-arg constructor required by Firebase Database
    constructor() : this(
        logId = UUID.randomUUID().toString(),
        shopId = "",
        action = "",
        module = "",
        oldValue = null,
        newValue = null,
        userDisplayName = "",
        userId = "",
        actorRole = "",
        ownerUid = "",
        targetId = null,
        timestamp = System.currentTimeMillis()
    )

    fun setLogId(value: Any?) {
        logId = value?.toString() ?: UUID.randomUUID().toString()
    }

    fun setOldValue(value: Any?) {
        oldValue = when (value) {
            is Map<*, *> -> runCatching { Gson().toJson(value) }.getOrNull()
            is String -> value
            else -> value?.toString()
        }
    }

    fun setNewValue(value: Any?) {
        newValue = when (value) {
            is Map<*, *> -> runCatching { Gson().toJson(value) }.getOrNull()
            is String -> value
            else -> value?.toString()
        }
    }
}
