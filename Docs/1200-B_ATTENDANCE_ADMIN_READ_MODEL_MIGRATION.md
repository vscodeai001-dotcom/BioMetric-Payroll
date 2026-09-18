# 1200-B Attendance Admin Read Model Migration

## Scope
Migrate the Admin Company Attendance Report's read of final calculated DailySummary records from SQL to the Firebase attendance projection.

## Completed
- Added an all-employee/date-range `GetDailySummariesAsync(from,to)` read method to `FirebaseAttendanceService`.
- Preserved the existing `DailySummary` model and all report aggregation/calculation code.
- `CompanyAttendanceReport.razor` now reads final DailySummary projection data from Firebase.
- SQL remains the authoritative boundary for attendance calculation and mutation operations.
- `PunchCorrectionApproval.razor` remains SQL-backed because it loads pending correction mutation records and approval state.
- No database schema changes.

## Validation
- Structural brace/parenthesis checks passed for modified files.
- Full .NET build was not run because the environment does not provide the dotnet SDK.
