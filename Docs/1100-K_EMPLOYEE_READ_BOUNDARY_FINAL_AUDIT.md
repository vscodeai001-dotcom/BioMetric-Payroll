# 1100-K Employee Read Boundary Final Audit

## Scope
Finalize the Web Employee-domain read-model migration after 1100-J without moving SQL-backed calculations, mutations, Identity linkage, or reconciliation responsibilities into an unsafe Firebase read.

## Implementation
- Added `FirebaseEmployeeManagementService.GetEmployeeNameMapAsync(...)` for batched Firebase employee-name projection reads.
- `MobileAdminPayrollController.History` now resolves display names from Firebase instead of querying `db.Employees`.
- Payroll preview/finalization continues to load the complete SQL Employee entity because `PayrollProcessorService` requires authoritative calculation inputs and the finalization path persists through the existing SQL boundary.

## Remaining Employee SQL references
All remaining `db.Employees` references were re-audited after this change. They are retained where the Employee entity participates in one or more of:

- Identity/account linkage and authentication validation.
- Mobile self-service authorization or business calculations.
- Payroll calculation/finalization inputs.
- Attendance processing and calculation.
- Leave/regularization/rostering/resignation/F&F mutations.
- Employee deletion or destructive lifecycle operations.
- Firebase Auth user management and Web Identity synchronization.
- Firebase provisioning/reconciliation, where SQL is the existing source used to project missing employee records into Firebase.

## Safety rule
Do not replace an Employee SQL query solely because it contains `db.Employees`. The query must first be classified as a pure projection read. If the full Employee entity is an input to an authoritative calculation, mutation, Identity boundary, or reconciliation process, keep the SQL boundary.

## Result
The safe pure employee display-name read in `MobileAdminPayrollController.History` is now Firebase-backed. Remaining Employee SQL access is intentional and documented rather than mechanically removed.

## Verification
- Source brace/parenthesis structural checks performed for modified files.
- Full .NET build was not executed because the environment does not have the required `dotnet` SDK available.
