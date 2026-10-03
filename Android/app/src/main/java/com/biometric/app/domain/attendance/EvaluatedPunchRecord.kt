package com.biometric.app.domain.attendance

import com.biometric.app.domain.attendance.PunchSourceTier

/**
 * Android mirror of the Web EvaluatedPunchRecord model.
 * Carries the result of the 3-tier punch evaluation engine per raw punch event.
 */
data class EvaluatedPunchRecord(
    val punchId: String,
    val staffId: String,
    val employeeName: String,
    val role: String?,
    val timestamp: Long,
    val dateStr: String,      // yyyy-MM-dd
    val timeStr: String,      // HH:mm:ss
    val rawDirection: String, // raw type field e.g. "IN","OUT","AUTO_IN","AUTO_OUT"
    val isIn: Boolean,
    val isOut: Boolean,
    val sourceTier: PunchSourceTier,
    val deviceId: String,
    val biometricId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val distanceFromGeofence: Double,
    val isAccepted: Boolean,
    val evaluationStatus: String, // ACCEPTED_IN | ACCEPTED_OUT | SUPPRESSED
    val reason: String,
    val ruleCategory: String,
    val sequenceOrder: Int
)
