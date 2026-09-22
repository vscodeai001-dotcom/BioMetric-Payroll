# Web → Android Native Functional Parity

## Target architecture

Firebase / SSOT is the common authoritative realtime source. Web and Android are two native presentations of the same application data and rules.

```text
Web change ──► Firebase/SSOT ──► Android immediately
Android change ──► Firebase/SSOT ──► Web immediately
```

No manual refresh, reload, logout/login, or app restart is required for normal data convergence.

## Existing native destinations mapped from Web

- Employee Records → `StaffActivity`
- Attendance Log → `AdminAttendanceActivity`
- Punch Corrections → `AdminManualPunchCorrectionActivity` / `PunchCorrectionApprovalActivity`
- Regularization → `RegularizationActivity`
- Payroll / Run Payroll / Payslip / Payroll History → `AdminPayrollActivity`
- Advances / Bonuses / Tax Declarations → `AdminFinanceActivity`
- Leave Management → `LeaveManagementActivity`
- Shift Scheduler → `ShiftManagerActivity`
- Company Settings / Feature Settings → `SettingsActivity`
- Holiday Management → `ShopClosedDaysActivity`
- User Management → `UserManagementActivity`
- Exit Management → `ExitManagementActivity`
- Report Center → `ReportCenterActivity`
- Audit Logs → `AuditTrailActivity`
- Recycle Bin → `RecycleBinActivity`
- Live Staff Tracking → `TrackingMapActivity`
- Location History / Route Replay → `RouteReplayActivity`
- Offline Tracking → `OfflineTrackingActivity`
- Geofence / Office → `GeofenceManagerActivity`
- Employee Permissions → `StaffPermissionActivity`

## Native realtime viewers added

The Web Parity hub also exposes native realtime viewers for report/information modules that do not have a dedicated Android workflow yet:

- Location Stays
- Attendance Event Monitoring
- Offline Tracking Details
- FBP Components
- Year-End Summary
- Statutory / Professional Tax Rules

These viewers read the owner-scoped Firebase/SSOT node continuously. CRUD for modules with business workflows remains in the dedicated native screen rather than bypassing existing business rules with a generic JSON editor.

## Non-negotiable rules

- Do not change the existing database schema.
- Do not create an Android-only source of truth.
- Do not duplicate payroll/attendance calculations in a generic UI.
- Reuse existing business logic and validation.
- Existing Android screens and navigation remain intact.
- Room remains local cache / temporary GPS delivery queue, not SSOT.
- Web and Android must converge through Firebase/SSOT in realtime.

## Native UI parity correction

The Android Admin parity hub must not expose generic/raw Firebase record viewers as substitutes for Web screens. Each Android destination must correspond to a real Web Admin module and use the Web module's data meaning, workflow, permissions, validation, and business rules while using a native Android layout.

The Admin Attendance screen was aligned to the Web `AttendanceLogViewer`: date range filtering, employee filtering, cumulative scheduled/worked/OT/penalty/lateness/break-penalty summaries, and attendance log cards. Android presentation remains native and mobile-friendly.

Generic table-only entries that had no corresponding native Web-module screen were removed from the parity hub so unrelated/raw-data layouts are not presented as if they were Web screen clones.
