# 1008 Report Center Full Parity

## Scope
- Attendance reports
- Financial reports
- Payroll reports
- Employee filters
- Date filters
- Drill-down behavior
- Existing export/print behavior
- Firebase SSOT data boundary

## Calculation boundary
Complex report calculations remain behind the existing Web/SQL `ReportService` boundary where they depend on the existing database models and payroll/attendance calculations. This module does not create a second payroll or attendance calculation engine.

The existing Web Report Center therefore remains the authoritative calculation surface for reports such as attendance aggregation, payroll variance, and financial register. The report result is generated from the existing calculation path without changing its formulas.

## Android report data
Android `ReportRepository` generates its existing report views from the Firebase-backed Room projection:

- `daily_summaries` for attendance reports
- `payroll_history` for payroll variance
- `payroll_history` + `employees` for financial register

The FirebaseRoomHydrator/FirebaseSyncManager already keeps the underlying SSOT collections synchronized into Room. No separate report database is introduced.

## Filters
- Attendance supports all employees or a selected employee.
- Date range is preserved.
- Payroll variance uses the selected end month and its immediately preceding month, matching the existing Web behavior.
- Financial register uses the selected year/month, matching the existing Web behavior.

## Drill-down
When a Web user selects an employee for the attendance report, the existing detailed daily-summary path is preserved. The Android report remains consistent with the same employee/date filtering semantics.

## Export / print
No existing export or print behavior was removed or rewritten. Existing dedicated Web report/export services remain available to their existing screens.

## Realtime
Report results are derived from Firebase-backed synchronized data. When underlying attendance/payroll records are synchronized, the next report generation uses the updated local projection without requiring a second database. Existing realtime listeners remain intact.

## Security
The report module does not broaden Firebase write access. Employee-scoped underlying data remains governed by the existing Firebase rules; Admin/SuperAdmin report access follows the existing role/feature gates.

## Compatibility
- Existing UI/layout preserved.
- Existing report definitions preserved.
- Existing Web/SQL calculation boundary preserved.
- No Neon dependency introduced.
- No Room schema migration introduced.
- No `MobileApiService` consumers removed blindly.
