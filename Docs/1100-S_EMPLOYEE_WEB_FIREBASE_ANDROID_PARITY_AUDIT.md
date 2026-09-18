# 1100-S Employee Web ↔ Firebase ↔ Android Parity Audit

## Scope

Final Employee-domain parity audit after 1100-R. The objective is to verify that Android Employee self-service uses the same Firebase Employee SSOT and security boundary as the Web Employee domain without reimplementing SQL business calculations.

## Verified parity

- Employee profile is read from `owners/{ownerUid}/employees/{employeeId}`.
- Employee self-service repository covers dashboard, attendance, payslips, leave, advances, bonuses, regularization, resignation, tax, FBP, and shifts through the Firebase projection.
- Android Room remains an offline projection/cache and is not treated as a second authoritative Employee source.
- Web Employee screens use Firebase Employee/profile/history services established by modules 1100-B through 1100-R.
- Web payroll/attendance/leave/roster calculations retain their documented server/SQL boundaries.
- Firebase realtime propagation is used for Employee changes.

## 1100-S correction

`FirebaseEmployeeSelfServiceRepository.employee()` previously attempted owner-wide collection enumeration and email fallback when the canonical Employee key was absent. That was incompatible with the Staff security rule finalized in 1100-P, where Staff must not enumerate `owners/{ownerUid}/employees`.

The runtime path is now canonical-key-only:

`owners/{ownerUid}/employees/{authenticatedEmployeeId}`

Legacy/generated-key migration compatibility is intentionally excluded from the Employee runtime client and remains a migration/provisioning concern.

## Realtime correction

`changesFlow()` previously listened to the entire owner node. It now listens only to:

`owners/{ownerUid}/employees/{authenticatedEmployeeId}`

This preserves realtime Employee self-service refresh while preventing unrelated owner-level changes from invalidating Employee screens.

## Deliberately retained boundaries

- MobileApiService for controlled legacy/server operations.
- SQL-backed payroll and complex business calculations.
- Server-side provisioning, Identity, and destructive operations.
- Room for offline projections.

## Acceptance criteria

- Staff can read only their canonical Employee record.
- Staff runtime never requires collection enumeration.
- Employee profile changes propagate in realtime.
- Unrelated owner changes do not trigger Employee self-service invalidation.
- No Employee business calculation is duplicated in Android.
