# 1013 Legacy API Removal Audit

## Purpose

Audit every active Android `MobileApiService` consumer without removing API calls blindly. Firebase is the cross-platform realtime SSOT, but operations that still depend on the existing Web/SQL transaction or calculation boundary remain behind the controlled API until a later migration proves parity.

## Decision matrix

| Area | Current Android API use | Decision | Reason |
|---|---|---|---|
| Auth | `login`, `firebase-session`, `me`, `logout` | KEEP controlled | Authentication/session/device binding is a security boundary. Firebase Auth is canonical for credentials, while the server boundary remains responsible for device/session lifecycle until the final auth audit. |
| Admin Finance | advance/bonus/tax/FBP endpoints | KEEP selectively | Firebase reads/projections exist, but approval and calculation-sensitive mutations must not bypass existing Web/SQL rules. |
| Admin Payroll | history/preview/finalize | KEEP | Existing Web/SQL payroll calculation and finalization are authoritative. |
| Admin Attendance | daily/company summary | KEEP controlled where calculation-backed | Attendance summaries depend on existing Web calculation services. Firebase is the realtime projection. |
| Punch Correction | pending/approve/reject/manual | KEEP mutation boundary | Approval/manual punch changes can affect attendance calculations and must use the existing server transaction boundary. |
| Leave | admin create/status/delete | KEEP mutation boundary | Existing leave balance, accrual, sandwich and payroll interactions remain authoritative. Firebase carries realtime projection. |
| Shift | legacy endpoints exist in interface | REMOVE consumer where no longer used; retain interface for compatibility | Current shift screens use Firebase-backed repositories. Do not delete the endpoint contract until a full dependency scan confirms no consumer. |
| Regularization | admin approval/status | KEEP mutation boundary | Approval injects into existing attendance/SQL calculation flow. Firebase carries request/status realtime state. |
| Exit/F&F | status, calculate settlement, finalize | KEEP | Full & Final settlement is a complex Web/SQL calculation and transaction boundary. Android must display server-calculated values, not recalculate them. |
| Theme | get/save theme | KEEP for now | User preference is scheduled for 1016; migrate after cross-device preference contract is finalized. |
| Employee data | employee/session/profile endpoints | Firebase for realtime projection; server boundary retained where privileged provisioning/session work is required | 1012 establishes employee/Auth/profile reconciliation. Do not remove provisioning/session APIs before 1010/1012 behavior is fully verified. |

## Direct consumers found in the 1012 master

- `AuthRepository.kt`
- `UserRepository.kt`
- `ThemePreferenceSync.kt`
- `GlobalSwitcherDelegate.kt`
- `LeaveManagementActivity.kt`
- `RegularizationActivity.kt`
- `AdminFinanceActivity.kt`
- `AdminPayrollActivity.kt`
- `ExitManagementActivity.kt`
- `PunchCorrectionApprovalActivity.kt`

## Explicitly migrated away from API where already wired

The audit confirms that the following areas have Firebase/Room repositories available from previous parity modules and should not be reintroduced as API-only reads:

- Employee self-service data
- Leave realtime projection/history
- Shift realtime projection/history
- Advance/bonus realtime data
- Payroll history display
- Admin attendance Firebase projection
- Report Center Firebase/Room projection
- Audit Viewer Firebase/Room projection
- Employee provisioning lifecycle

## Rules for subsequent API removal

1. Remove a consumer only after the corresponding Firebase path has an employee/admin role-secure read/write contract.
2. Preserve Web/SQL calculations that cannot be safely reproduced on Android.
3. Keep privileged operations behind the server boundary.
4. Never make Android use Firebase Admin SDK credentials.
5. Every removed API consumer must have a replacement repository/test path.
6. API removal must not change an existing screen or calculation result.
7. After each removal, verify online, offline, reconnect, duplicate-write and realtime behavior.

## 1013 result

**No destructive API removal is performed in this module.** The active consumers are classified and the safe migration boundary is documented. This avoids breaking currently working payroll, attendance, authentication, F&F, leave and approval calculations.
