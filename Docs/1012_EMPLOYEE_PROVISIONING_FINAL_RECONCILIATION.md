# 1012 Employee Provisioning Final Reconciliation

## Purpose

Reconcile the existing Web Employee record with Firebase employee data, Firebase Authentication claims, `user_profiles`, and the Android single-device session without changing the existing Employee UI or payroll/attendance calculations.

## Contract

- Existing Web/SQL Employee row remains the authoritative business-data source for employee fields and calculations.
- Firebase `owners/{ownerUid}/employees/{employeeId}` is the cross-platform realtime SSOT projection.
- Firebase Auth custom claims carry `role`, `employee_id`, and `owner_uid`.
- `user_profiles/{uid}` mirrors the user-facing identity.
- Deactivation disables Firebase Auth, revokes refresh tokens, and removes the Firebase employee session.
- Active employees are never silently re-enabled if an administrator intentionally disabled their Firebase Auth account.
- An employee record linked to an Admin/SuperAdmin Firebase account is rejected rather than overwriting the administrative identity.
- If no Firebase Auth account exists, the employee record is synchronized and `employee_provisioning_status/{employeeId}` records `PENDING_AUTH_PROVISIONING`; the service never invents a password or creates credentials implicitly.

## Realtime lifecycle

Web employee create/update/delete -> existing SQL transaction -> immediate Firebase employee projection -> Firebase listeners -> Android Room/UI.

The background reconciliation repeats every five minutes to repair missed wiring or older records.
