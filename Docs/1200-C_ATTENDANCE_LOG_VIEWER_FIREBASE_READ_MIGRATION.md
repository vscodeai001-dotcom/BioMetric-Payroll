# 1200-C Attendance Log Viewer Firebase Read Migration

## Scope
Migrate the Attendance Log Viewer read model for finalized daily summaries and canonical attendance punches to the existing Firebase Realtime Database SSOT.

## Preserved SQL boundaries
SQL remains intentionally used by the viewer for operations that mutate or recalculate attendance, including automatic punch processing, approved-leave synchronization, fingerprint seeding, and explicit Re-Process flows. SQL is also retained for schedule/configuration inputs used by the existing calculation engine and display calculations.

## Firebase read paths
- `daily_summaries` is read through `FirebaseAttendanceService.GetDailySummariesAsync(from, to)`.
- `attendance_punches` is read through `FirebaseAttendanceService.GetAttendancePunchesAsync(from, to, employeeId?)`.
- Existing employee filtering remains applied after Firebase retrieval.

## Architecture rule
Do not introduce Firestore into this module. The application continues to use the existing Firebase Realtime Database abstraction (`FirebaseRealtimeService`) and owner-scoped SSOT schema.

## Calculation rule
The migration does not rewrite attendance formulas. The existing AttendanceCalculatorService remains the calculation boundary. The viewer consumes finalized Firebase projections after any required SQL-backed processing step.

## Verification
- Source braces and parentheses balanced.
- Existing FirebaseAttendanceService registration retained.
- No remaining `AttendanceLogs` or `DailySummaries` reads were removed from mutation/reprocessing methods.
- Full .NET build not run because the environment does not have the dotnet SDK.
