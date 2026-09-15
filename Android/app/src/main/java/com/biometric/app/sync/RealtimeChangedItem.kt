package com.biometric.app.sync

/**
 * Describes one entity change published through Firebase owner_events.
 *
 * FirebaseSyncManager accepts both the legacy JSON property names
 * (Entity/Action/RecordId) and the Kotlin property names where applicable.
 */
data class RealtimeChangedItem(
    val entity: String = "",
    val action: String = "MODIFIED",
    val recordId: String? = null
)
