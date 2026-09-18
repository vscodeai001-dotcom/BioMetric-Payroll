# 1200-D Attendance Realtime Refresh + Firebase Event Integration

## Scope

Connect the Attendance Log Viewer to the existing Firebase Realtime Database application-event channel while preserving the existing attendance calculation and mutation boundaries.

## Implementation

`wwwroot/js/attendance-refresh.js` now classifies the following application entities as Attendance Log Viewer inputs:

- Employee
- AttendanceLog
- DailySummary
- LeaveRequest
- ShiftSchedule
- CompanyHoliday
- AttendanceRegularization

When a Firebase application event contains one of these entities, the existing debounced `RefreshFromNotification` path is invoked.

Unrelated events such as PayrollHistory, SalaryAdvance, BonusRecord, TaxDeclaration, or FBP changes do not trigger the Attendance Log Viewer.

## Boundary

Realtime notification is an invalidation signal only. `AttendanceLogViewer.LoadLogs()` remains responsible for loading the current state and the existing automatic punch processing/recalculation behavior remains unchanged.

No SignalR service contract was removed. The existing viewer registration remains compatible with the legacy notification path.

## Validation

- JavaScript syntax check: PASS
- Brace balance: PASS
- Parenthesis balance: PASS
- No Firestore dependency introduced
- Full .NET build: not run because dotnet SDK is unavailable in the execution environment
