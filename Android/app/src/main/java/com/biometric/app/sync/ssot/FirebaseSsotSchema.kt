package com.biometric.app.sync.ssot

/**
 * Canonical Firebase Realtime Database contract shared by Web and Android.
 * Firebase is the cross-platform authoritative store. Room is retained only
 * as Android's local/offline cache and queue during migration.
 */
object FirebaseSsotSchema {
    const val ROOT = "owners"
    const val DEFAULT_OWNER_UID = "biometricpayroll"

    val tables: Map<String, String> = linkedMapOf(
        "Employee" to "employees",
        "Shop" to "shops",
        "AttendanceLog" to "attendance",
        "AttendancePunch" to "attendance_punches",
        "SalaryAdvance" to "advance_payments",
        "EmployeeHistory" to "employee_history",
        "CompanyHoliday" to "shop_closed_days",
        "AttendanceRegularization" to "regularizations",
        "LeaveRequest" to "leave_requests",
        "ResignationRequest" to "resignation_requests",
        "SalarySnapshot" to "salary_snapshots",
        "AuditLog" to "audit_logs",
        "DailySummary" to "daily_summaries",
        "ShiftSchedule" to "shift_schedules",
        "PayrollHistory" to "payroll_history",
        "PayrollPreview" to "payroll_previews",
        "PayrollFinalization" to "payroll_finalization",
        "BonusRecord" to "bonus_records",
        "TaxDeclaration" to "tax_declarations",
        "FBPComponent" to "fbp_components",
        "FlexibleBenefitDeclaration" to "fbp_declarations",
        "FeatureSettings" to "feature_settings",
        "CompanySetting" to "company_settings",
        "ProfessionalTaxSlab" to "professional_tax_slabs",
        "YearEndSummary" to "year_end_summaries",
        "FnFSettlement" to "fnf_settlements",
        "ReportDefinition" to "report_definitions",
        "GeoPunchAudit" to "geo_punch_audits"
    )

    fun tablePath(ownerUid: String, table: String): String =
        "$ROOT/${escape(ownerUid)}/${escape(table)}"

    fun recordPath(ownerUid: String, table: String, recordId: String): String =
        "${tablePath(ownerUid, table)}/${escape(recordId)}"

    fun isTable(table: String?): Boolean =
        !table.isNullOrBlank() && tables.values.any { it == table.trim() }

    private fun escape(value: String): String = value.trim()
        .replace(".", "_dot_")
        .replace("#", "_hash_")
        .replace("$", "_dollar_")
        .replace("[", "_open_")
        .replace("]", "_close_")
        .replace("/", "_slash_")
}
