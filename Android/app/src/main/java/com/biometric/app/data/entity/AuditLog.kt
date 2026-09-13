package com.biometric.app.data.entity

import androidx.annotation.Keep
import java.util.UUID

@Keep
data class AuditLog(
    var logId: String = UUID.randomUUID().toString(),
    var shopId: String = "",
    var action: String = "", // ADD, UPDATE, DELETE, RESTORE, VIEW, LOGIN_FAIL
    var module: String = "", // Staff, Attendance, Shop, Approvals, Tracking, etc.
    var oldValue: String? = null,
    var newValue: String? = null,
    var userDisplayName: String = "",
    var userId: String = "",
    var timestamp: Long = System.currentTimeMillis()
)
