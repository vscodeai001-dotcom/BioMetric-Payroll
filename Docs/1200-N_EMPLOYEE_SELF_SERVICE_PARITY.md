# 1200-N Employee Self-Service Parity

Built from the 1200-M Background Tracking → Attendance base.

## Scope

Align the Android employee portal with the existing Web employee self-service feature permissions while keeping the existing Android screens, navigation structure, Firebase SSOT model, and business calculations intact.

## Implemented

- Firebase `feature_settings/1` is read by the employee self-service repository.
- Employee tools are filtered using the same parent/child feature relationships used by the Web employee screens.
- Payroll + payslip visibility requires both `enablePayroll` and `employeeCanViewPayslip`.
- Leave access requires `enableLeaveManagement` + `employeeCanViewLeave`; leave history additionally honors `employeeCanViewLeaveHistory`.
- Advance, bonus, shift, resignation, tax, FBP, regularization, and custom-report access are gated by their corresponding existing feature flags.
- Repository read operations enforce the same access boundary, so hiding a tool is not the only protection.
- Employee-created leave, advance, regularization, resignation, tax, and FBP writes enforce the corresponding feature permission before Firebase mutation.
- Dashboard no longer fails merely because payslips are disabled. The latest payslip is omitted while the rest of the employee dashboard remains available.
- Existing employee-scoped Firebase queries and database rules are preserved.
- No new database schema or UI screen structure was introduced.

## Existing Web parity reference

The Web employee portal already applies `EmployeeCanView*` feature checks across Attendance, Leave, Payslips, Reports, Resignation, Tax, and related modules. This Android module now consumes the same Firebase-projected feature settings rather than maintaining a second permission configuration.

## Security boundary

Firebase RTDB employee scoping remains authoritative. Feature visibility is an additional application-level permission layer and does not replace RTDB rules.
