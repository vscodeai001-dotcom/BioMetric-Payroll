package com.biometric.app.domain.audit

import com.biometric.app.data.entity.AuditLog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuditLogger @Inject constructor() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    fun logAction(
        shopId: String?,
        action: String,
        module: String,
        oldValue: String? = null,
        newValue: String? = null,
        targetId: String? = null
    ) {
        val user = auth.currentUser
        val logId = UUID.randomUUID().toString()
        
        val log = hashMapOf(
            "logId" to logId,
            "shopId" to (shopId ?: "GLOBAL"),
            "action" to action,
            "module" to module,
            "oldValue" to oldValue,
            "newValue" to newValue,
            "targetId" to targetId,
            "userId" to (user?.uid ?: "unknown"),
            "userDisplayName" to (user?.displayName ?: user?.phoneNumber ?: "System"),
            "timestamp" to System.currentTimeMillis()
        )

        db.collection("auditLogs").document(logId).set(log)
    }
}
