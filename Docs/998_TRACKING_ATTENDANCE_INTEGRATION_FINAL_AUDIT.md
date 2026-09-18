# 998 Tracking Start/Stop + Attendance Integration Final Audit

## Scope

This pass audits the boundary between Android GPS tracking and attendance punching.
It does not replace the Web/SQL attendance calculation engine.

## Policy hierarchy

1. Firebase/Web attendance policy is re-read immediately before a manual punch write.
2. Geo-Fencing is the master switch.
3. Dual Attendance disables the manual punch path.
4. Automatic Geofence Punching disables the manual punch path.
5. Manual punching requires a valid current location inside the configured radius.
6. Automatic geofence reconciliation remains Web-authoritative.

## Race protection

The UI can render a manual PUNCH button and an administrator can change policy before the user taps it.
`EmployeeHomeActivity.attemptPunch()` now re-reads and normalizes the Firebase policy immediately before creating the punch.
The punch is rejected when the current policy no longer permits manual punching or when the current Firebase office/radius configuration is unavailable.

## Duplicate punch protection

Android manual punches continue to use a unique `punchId`, are persisted locally first, and are written through the existing Firebase sync path. The UI disables the button while the operation is in progress.
Automatic geofence punching is not duplicated in Android; the Web attendance engine remains authoritative for that operation.

## Geofence boundary

The final guard uses the configured radius directly. No Android-only +1m tolerance is applied.

## Tracking lifecycle

Tracking start/stop remains governed by authentication, employee lifecycle, attendance policy, effective tracking scope, and shift/custom window resolution. Stopping tracking ends the corresponding Firebase GPS session. This pass does not alter payroll or attendance calculations.
