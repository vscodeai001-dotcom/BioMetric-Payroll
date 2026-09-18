# 1100-E Employee-domain consumer migration

## Objective
Move Web employee-domain **read consumers** from EF/SQL employee reads to the Firebase Employee SSOT projection, while preserving existing screens, business rules, calculations, and mutation boundaries.

## Migrated read consumers
- `MainLayout.razor` employee identity lookup
- `LeaveSettingsTab.razor` active employee list
- `ManualPunchCorrection.razor` employee lookup and employee selector
- `AttendanceLogViewer.razor` employee lists, filters, and export-side employee projection
- `PunchCorrectionApproval.razor` employee list and employee lookup used by recalculation
- `LeaveManagement.razor` employee selector
- `UserManagement.razor` employee projection list
- `YearEndSummaryViewer.razor` employee display list
- `ReportCenter.razor` employee filter list
- `AdminTaxDeclarations.razor` employee list
- `ExitManagement.razor` employee list
- `FBPDeclarationApproval.razor` employee lookup dictionary
- `OfflineTracking.razor` employee selector list
- Employee self screens: `MyLeaveRequest`, `EmployeeHome`, `MyFBPDeclaration`, `MyRegularization`, `MyReports`, `MyPayslips`, `MyLeaveHistory`, `MyTaxDeclaration`, `MyAttendanceViewer`, `MyResignation`
- `DashboardAnalyticsService` employee names, active employee count, and employee leave-balance lookup

## Shared Firebase access
`FirebaseEmployeeManagementService` now exposes:
- `GetEmployeesAsync()`
- `GetEmployeeAsync(int employeeId)`
- `GetEmployeeByEmailAsync(string? email)`

The email lookup is a Firebase projection lookup and is case-insensitive after trimming.

## Intentionally retained SQL/EF boundaries
This module does **not** blindly remove all `db.Employees` references. Remaining references are retained where the operation is a mutation, lifecycle/security/reconciliation boundary, or part of a calculation/storage join that has not yet been independently replaced.

Examples include:
- employee deletion/recycle-bin mutation
- leave-balance mutation
- identity/user linking operations
- payroll/attendance/regularization/resignation calculations and mutation services
- Firebase Auth provisioning/reconciliation lifecycle
- tracking queries that still join SQL tracking records to employee rows

These are candidates for later modules and must be migrated individually with behavior validation.

## Compatibility
- Existing `Employee` model and UI components are unchanged.
- Existing feature gates remain in place.
- Existing SQL-backed calculations remain SQL-backed.
- No Firebase Admin SDK is introduced into the browser/client side.
- Firebase remains the employee projection/SSOT for migrated reads.

## Verification performed
- Structural brace checks passed for all modified C#/Razor files.
- Employee-domain consumer grep was re-run after migration.
- `dotnet` is not available in the current execution environment, so a real .NET build could not be executed here.

## Next boundary
The next employee-domain work should audit the remaining SQL employee consumers individually, especially tracking joins, identity/provisioning lifecycle, payroll calculation boundaries, and mutation paths. No global search-and-replace should be used.
