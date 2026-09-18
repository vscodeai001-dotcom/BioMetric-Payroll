# 1100-I Employee SQL Dependency Audit

## Purpose
Final employee-domain dependency audit after 1100-H. Remove only read-only Employee dependencies whose data is already projected into Firebase. Preserve SQL where it is an intentional mutation, calculation, identity-link, reconciliation, or compatibility boundary.

## Firebase-first read boundaries completed in 1100-I
- FBPService employee notification name/email lookup -> Firebase Employee SSOT.
- NotificationService employee profile/email lookup -> Firebase Employee SSOT; ASP.NET Identity account resolution remains server-side.
- Admin OfflineTrackingDetails employee roster and employee names -> Firebase Employee SSOT; GPS history/session records remain SQL-backed.
- Admin OfflineTracking offline GPS employee names were already Firebase-first; SQL GPS history remains read-only tracking data.

## Remaining SQL Employee references and boundary classification
| Area | Classification | Action |
|---|---|---|
| MobileAdminPunchController | attendance/punch mutation + employee data | RETAIN SQL calculation/mutation boundary; migrate only pure roster/name reads in a later isolated controller pass |
| MobileAdminLeaveController | leave mutation + employee data | RETAIN mutation boundary; pure name lookup can move with contract tests |
| MobileAdminPayrollController | payroll calculation/finalization | RETAIN SQL calculation boundary |
| MobileAdminRegularizationController | regularization mutation/calculation | RETAIN boundary |
| MobileAdminShiftController | shift operations | RETAIN until shift repository contract is verified |
| MobileEmployeeController | employee self-service calculations/mutations | RETAIN where request requires authoritative SQL calculation state |
| MobileAdminAttendanceController | attendance calculation/mutation | RETAIN |
| MobileTokenAuthenticationHandler | legacy authentication/device lock | RETAIN compatibility security boundary |
| Logout.cshtml.cs | identity/account lifecycle | RETAIN |
| ResignationService | resignation/F&F mutation and calculation | RETAIN |
| AttendanceEventMonitorService | attendance event processing | RETAIN |
| EmployeeSingleSessionSignInManager | Identity + device/session enforcement | RETAIN |
| FirebaseEmployeeProvisioningReconciliationService | SQL employee source -> Firebase reconciliation | RETAIN intentionally |
| RegularizationService | business calculation/mutation | RETAIN |
| FirebaseUserManagementService | Identity linkage/provisioning | RETAIN |
| FirebaseEmployeeProvisioningService | provisioning/reconciliation source | RETAIN |
| LeaveAccrualService | leave balance calculation/mutation | RETAIN |
| EmployeeDeletionService | destructive employee mutation | RETAIN |
| RosteringService | schedule mutation/calculation | RETAIN |
| LeaveManagementService | leave mutation/balance update | RETAIN |
| RecycleBinManager | restore/permanent delete mutation | RETAIN |

## Safety rule
Do not perform a global `db.Employees` replacement. Every remaining reference must be classified before removal. Firebase is the employee projection/identity read source; SQL remains the authoritative server boundary for complex calculations, destructive mutations, legacy authentication, and reconciliation.
