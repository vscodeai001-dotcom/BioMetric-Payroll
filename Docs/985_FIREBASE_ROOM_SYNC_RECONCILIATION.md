# 985 - Firebase ↔ Room Sync Reconciliation

## Purpose
Close the cache-correctness gap in the Android Firebase/Room projection without changing payroll, attendance, leave, shift, or other business calculations.

## Contract
Firebase remains the Android realtime synchronization source. Room remains the local/offline projection. Child listeners handle live add/change/remove events. A one-time Firebase snapshot reconciliation also removes stale Room rows that are already marked synced but no longer exist in Firebase.

## Offline safety
Rows with `syncState == 0` are treated as pending local writes and are never removed merely because they are absent from the current Firebase snapshot. This prevents an offline write from being silently discarded.

## Covered core tables
- shops
- employees
- attendance
- advance_payments
- employee_history
- shop_closed_days
- regularizations
- attendance_punches
- leave_requests
- resignation_requests

## Delete semantics
A Firebase child deletion removes only the matching Room projection row. Employee deletion from the existing Staff flow remains a soft deactivation (`isActive=false`) rather than destructive removal. Historical payroll/attendance data is not cascade-deleted by the lifecycle sync layer.

## Scope guard
This module does not rewrite calculation engines or move SQL business logic. It only improves Firebase-to-Room projection consistency and offline safety.

## Verification
Kotlin source brace/structure checks were run. A full Gradle build remains environment-blocked when Gradle attempts to resolve `services.gradle.org`.
