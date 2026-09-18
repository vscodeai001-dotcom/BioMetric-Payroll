# 1100-H Employee Authentication / Provisioning / Lifecycle Boundary

## Firebase SSOT path
- Firebase ID-token verification remains the authentication boundary.
- Employee identity/profile resolution uses `FirebaseEmployeeManagementService`.
- Employee Firebase session state is checked under `employee_sessions/{uid}`.
- Employee role, employee_id and owner_uid come from Firebase custom claims.

## Legacy compatibility path
- Opaque mobile tokens remain supported.
- Their device lock and employee-link validation remain SQL-backed intentionally.
- This path is not silently removed because it is a compatibility/security boundary.

## Privileged server boundaries
- `FirebaseUserManagementService` retains SQL Identity/Employee linkage because creating, changing, disabling and deleting privileged accounts must reconcile Web Identity, Employee linkage and Firebase Auth atomically as far as the existing architecture permits.
- `FirebaseEmployeeProvisioningService` and `FirebaseEmployeeProvisioningReconciliationService` retain the SQL Employee row as the canonical payroll/business-data source for provisioning and reconciliation.
- Administrative Firebase Auth operations remain server-only. Android/Web clients do not directly administer Firebase Auth users.

## Safety invariants
1. Employee records linked to Admin/SuperAdmin Firebase identities are rejected.
2. Missing Firebase Auth accounts are marked pending; no password is invented.
3. Deactivated employees do not get silently re-enabled.
4. Firebase-native employee authentication no longer performs an SQL Employee read.
5. Existing UI, payroll calculations and mutation behavior are preserved.
