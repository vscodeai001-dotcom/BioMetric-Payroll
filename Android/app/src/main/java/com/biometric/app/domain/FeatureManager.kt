package com.biometric.app.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeatureManager @Inject constructor() {

    enum class Feature(val displayName: String, val emoji: String) {
        DASHBOARD("Dashboard", "📊"),
        STAFF("Staff Management", "👥"),
        ATTENDANCE("Attendance Logs", "📅"),
        PAYROLL("Payroll Hub", "💰"),
        APPROVALS("Admin Approvals", "✅"),
        TRACKING("Live Operations", "🛰️"),
        REPORTS("Analytics & Insights", "📈"),
        AUDIT_TRAIL("Audit Trail", "📋"),
        RECYCLE_BIN("Recycle Bin", "🗑️"),
        AI_ASSISTANT("Workforce AI", "🧠"),
        GEOFENCING("Geofence Manager", "🗺️"),
        SHIFTS("Shift Manager", "🕒")
    }

    private val _enabledFeatures = MutableStateFlow<Set<Feature>>(Feature.entries.toSet())
    val enabledFeatures: StateFlow<Set<Feature>> = _enabledFeatures.asStateFlow()

    fun setFeatures(features: Set<Feature>) {
        _enabledFeatures.value = features
    }

    fun isFeatureEnabled(feature: Feature): Boolean {
        return _enabledFeatures.value.contains(feature)
    }

    fun getAllFeatures(): List<Feature> = Feature.entries
}
