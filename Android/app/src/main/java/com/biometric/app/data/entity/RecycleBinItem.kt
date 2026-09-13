package com.biometric.app.data.entity

import androidx.annotation.Keep
import java.util.UUID

@Keep
data class RecycleBinItem(
    var id: String = UUID.randomUUID().toString(),
    var recordId: String = "",
    var shopId: String? = null,
    var module: String = "", // STAFF, ATTENDANCE, SHOP, etc.
    var actionType: String = "DELETED", // DELETED, UPDATED
    var snapshotJson: String = "",
    var previousValueJson: String? = null,
    var timestamp: Long = System.currentTimeMillis(),
    var userId: String = "",
    var userName: String = "",
    var itemName: String = "" // Display name like "Staff: John" or "Cash: ₹500"
)
