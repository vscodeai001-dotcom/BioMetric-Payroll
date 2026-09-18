# 1100-J Employee Read-Model Finalization

## Scope
Move remaining safe, read-only Employee roster/name lookups in the mobile admin and live-location Web paths to `FirebaseEmployeeManagementService`, while preserving SQL/EF for authoritative mutations, attendance/payroll calculations, Identity lifecycle, and reconciliation.

## Migrated in 1100-J
- `MobileAdminPunchController.Issues`: active employee roster now comes from Firebase; `Recalculate` intentionally retains SQL Employee lookup because it feeds attendance calculation.
- `MobileAdminPunchController.Pending`: employee name map now comes from Firebase.
- `MobileAdminLeaveController`: employee roster and create-response name now come from Firebase; leave persistence/status remains in `LeaveManagementService`.
- `MobileAdminRegularizationController`: employee names now come from Firebase; regularization state/mutation remains SQL-backed service logic.
- `MobileAdminShiftController`: employee name map now comes from Firebase; shift schedule mutation and rostering remain SQL-backed.
- `MobileAdminAttendanceController`: employee roster/filter is now Firebase; daily summaries and punches remain SQL-backed calculation/reporting data.
- `LiveStaffLocationPanel.LoadEmployees`: live employee roster now comes from Firebase.
- `LiveStaffLocationPanel.RefreshLocations`: SQL is used only for GPS session lifecycle/data, filtered by the Firebase employee IDs. The SQL Employee join was removed.

## Intentionally retained SQL Employee references
These are not treated as removable read-only dependencies merely to reduce `db.Employees` count:
- Payroll preview/finalization: SQL Employee entities are inputs to existing payroll calculation/finalization boundaries.
- Mobile employee self-service: existing SQL calculation/mutation boundaries remain.
- Legacy mobile bearer authentication and Identity logout/session enforcement remain server-side.
- Resignation/F&F, regularization, leave accrual/management, rostering, attendance event processing, and employee deletion remain authoritative SQL mutation/calculation paths.
- Firebase provisioning/reconciliation and Firebase user-management keep SQL Employee linkage as an explicit server boundary.

## Safety invariants
1. Firebase is used for shared Employee roster/profile reads where the data is already projected.
2. SQL calculation and mutation logic is not replaced by a profile read.
3. No Firebase Admin SDK is introduced into Android.
4. Existing API contracts and DTO shapes are preserved.
5. Full .NET build validation is not claimed when the SDK/toolchain is unavailable.
