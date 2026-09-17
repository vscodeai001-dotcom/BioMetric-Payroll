package com.biometric.app.api

/**
 * 1013 Legacy API Removal Audit.
 *
 * This is intentionally a policy/diagnostic contract, not a second API client.
 * It prevents future work from removing MobileApiService calls merely because
 * Firebase has a realtime projection. Calculation-sensitive and privileged
 * operations remain behind the controlled Web/server boundary until parity is
 * explicitly verified.
 */
object LegacyApiBoundaryPolicy {
    enum class Decision {
        FIREBASE_REPLACEMENT,
        CONTROLLED_SERVER_BOUNDARY,
        PENDING_1016_THEME,
        COMPATIBILITY_ONLY
    }

    val firebaseReadAreas = setOf(
        "employee-self-service",
        "leave-history",
        "shift-history",
        "advance-history",
        "bonus-history",
        "payroll-history",
        "admin-attendance-projection",
        "reports",
        "audit-viewer",
        "employee-provisioning-projection"
    )

    val controlledServerAreas = setOf(
        "auth-session",
        "payroll-preview",
        "payroll-finalization",
        "attendance-calculation",
        "punch-approval",
        "leave-mutation",
        "regularization-approval",
        "tax-approval",
        "fbp-calculation",
        "advance-calculation-sensitive-mutation",
        "bonus-calculation-sensitive-mutation",
        "exit-fnf-calculation",
        "user-management"
    )

    const val THEME_AREA = "theme-preference"
}
