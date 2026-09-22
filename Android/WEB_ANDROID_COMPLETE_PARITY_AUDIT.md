# Web → Android Complete Functional Parity Audit

Date: 2026-09-22

## Contract

Android is a native mobile presentation of the Web application. The Web application is the functional reference. Firebase/SSOT is the shared realtime source. Android UI may differ visually, but the corresponding Web module's purpose, fields, filters, CRUD operations, permissions, validation, calculations, statuses, approval rules, and business flow must remain equivalent.

Realtime requirement: Web ↔ Firebase/SSOT ↔ Android. No manual refresh, reload, logout/login, or app restart is required for normal data convergence.

Room is not SSOT. Room is only local cache / temporary GPS failed-delivery queue.

## Route audit

| Web route | Web module | Android destination | Status |
|---|---|---|---|
| `/` | Admin Dashboard | `MainActivity` | Native implemented |
| `/employees` | Employee Records | `StaffActivity` | Native implemented |
| `/employees/details/{EmployeeId:int}` | Employee Details | `StaffDetailActivity` | Native implemented |
| `/attendancelogs` | Attendance Daily Logs | `AdminAttendanceActivity` | Native implemented |
| `/company-attendance-report` | Company Attendance Report | `ReportCenterActivity` | Shared native report workflow |
| `/manual-punch-correction` | Manual Punch Correction | `AdminManualPunchCorrectionActivity` | Native implemented |
| `/attendance/punch-approval` | Punch Approval | `PunchCorrectionApprovalActivity` | Native implemented |
| `/attendance/regularization-approval` | Regularization Approval | `RegularizationActivity` | Native implemented |
| `/schedule` | Shift Scheduler | `ShiftManagerActivity` | Native implemented |
| `/leave-management` | Leave Management | `LeaveManagementActivity` | Native implemented |
| `/run-payroll` | Run Payroll | `AdminPayrollActivity` | Native implemented |
| `/payslip/{PayrollId:int}` | Payslip | `AdminPayrollActivity` / payroll detail flow | Shared native payroll workflow |
| `/salary-advances` | Salary Advances | `AdminFinanceActivity` | Native implemented |
| `/bonus-management` | Bonus Management | `AdminFinanceActivity` | Native implemented |
| `/admin/tax-declarations` | Admin Tax Declarations | `AdminFinanceActivity` | Native implemented |
| `/admin/fbp-components` | FBP Component Setup | `AdminFinanceActivity` / FBP finance workflow | Shared native finance workflow |
| `/admin/fbp-approval` | FBP Declaration Approval | `AdminFinanceActivity` / FBP finance workflow | Shared native finance workflow |
| `/admin/year-end-summary` | Year-End Summary | `AdminPayrollActivity` / payroll workflow | Shared native payroll workflow |
| `/admin/exit-management` | Exit & Settlement | `ExitManagementActivity` | Native implemented |
| `/settings/company` | Company Settings | `SettingsActivity` | Native implemented |
| `/admin/feature-toggles` | Feature Toggles | `SettingsActivity` | Native implemented |
| `/holidays` | Holiday Management | `ShopClosedDaysActivity` | Native implemented |
| `/admin/users` | User Management | `UserManagementActivity` | Native implemented |
| `/admin/report-center` | Report Center | `ReportCenterActivity` | Native implemented |
| `/admin/audit-logs` | Audit Logs | `AuditTrailActivity` | Native implemented |
| `/admin/recycle-bin` | Recycle Bin | `RecycleBinActivity` | Native implemented |
| `/admin/location-tracking-history` | Location Tracking History | `RouteReplayActivity` | Native implemented |
| `/admin/offline-tracking` | Offline Tracking | `OfflineTrackingActivity` | Native implemented |
| `/admin/offline-tracking-details` | Offline Tracking Details | `OfflineTrackingActivity` | Shared native tracking workflow |
| `/admin/location-stays` | Location Stays | `RouteReplayActivity` / tracking workflow | Shared native tracking workflow |
| `/admin/attendance-event-monitoring` | Attendance Event Monitoring | `AdminAttendanceActivity` / attendance workflow | Shared native attendance workflow |

## Employee self-service routes already represented by native Android screens

- `/employee-home` → `EmployeeHomeActivity`
- `/my-attendance` → `AttendanceLogsActivity`
- `/my-leave-request` → `ApplyLeaveActivity` / fragment
- `/my-leave-history` → `MyLeavesActivity`
- `/my-shifts` → `ShiftScheduleActivity`
- `/my-salary-advances` → `SalaryAdvancesActivity`
- `/my-tax-declaration` → `TaxDeclarationActivity`
- `/my-bonuses` → `BonusesActivity`
- `/my-fbp-declaration` → `FbpDeclarationActivity`
- `/my-regularization` → `MyRegularizationsActivity`
- `/my-reports` → `MyReportsActivity`
- `/my-resignation` → `ResignationActivity`
- `/my-payslips` → `PayslipListActivity`

## Important parity finding

The current Android project already contains native implementations for most major Web Admin workflows. The remaining parity work is primarily **deep screen-by-screen behavior verification**, not creation of unrelated generic Firebase record screens.

Do not introduce a generic JSON/table viewer as a substitute for a Web module. When a Web route is represented by a combined Android workflow, that Android workflow must use the same Web data contract and existing business APIs/rules.

## Acceptance tests for every module

1. Change data in Web → Firebase/SSOT → Android updates without refresh.
2. Change data in Android → Firebase/SSOT → Web updates without refresh.
3. CRUD uses the same validation and permission boundary as Web.
4. Payroll/attendance calculations are not reimplemented differently on Android.
5. Existing database schema is unchanged.
6. Existing Android GPS tracking and temporary Room fallback remain unchanged.
7. No logout/login is required to obtain current Firebase data.
8. Long-running Android Admin sessions automatically recover realtime listeners.

## Screens that must not be reintroduced

- Generic Firebase JSON viewer as a replacement for a real Web screen.
- Android-only database as an authoritative source.
- Android-only payroll/attendance calculation path.
- Unrelated dashboard cards or screens with no Web feature counterpart.
