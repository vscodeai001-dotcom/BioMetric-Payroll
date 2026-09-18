# 1100-P Employee Firebase Security Rules + Role/Scope Final Audit

## Scope

This module finalizes the Realtime Database security boundary for the canonical
Employee projection at `owners/{ownerUid}/employees/{employeeId}`.

The rule set is intentionally compatible with the existing Web/Android contract:
- `owner_uid` identifies the tenant/owner scope.
- `role` accepts the existing Admin/SuperAdmin spellings.
- `employee_id` identifies the employee self-service scope.
- The Employee record key is the canonical Employee ID.

## Employee access matrix

| Actor | Read employee collection | Read own employee record | Read another employee | Write employee records |
|---|---:|---:|---:|---:|
| SuperAdmin | Yes, own owner scope | Yes | Yes | Yes |
| Admin | Yes, own owner scope | Yes | Yes | Yes |
| Employee/Staff | No collection read | Yes | No | No |
| Unauthenticated | No | No | No | No |

The collection-level read is restricted to Admin/SuperAdmin so a Staff token
cannot enumerate the employee roster. A Staff token may directly read only the
record matching its `employee_id` claim.

## Tenant boundary

Both collection and record operations require:

`auth.token.owner_uid == $uid || auth.uid == $uid`

This keeps the Firebase owner path tenant-scoped. The owner UID itself is the
path boundary and is not inferred from Employee profile data.

## Record integrity

The Employee child rule validates:
- `employeeId` exists and matches `$employeeId`.
- `isActive` exists and is boolean.
- `_entity`, when present, must be `Employee`.
- `_key`, when present, must match `$employeeId`.
- `_revision`, when present, must be numeric.
- `_writeId`, when present, must be a string.
- `_updatedUtc`, when present, must be a string.

These checks support module 1100-O's revision/write metadata without requiring
legacy records to already contain the metadata fields.

## Deliberate non-goals

- Firebase Security Rules do not replace server-side payroll/attendance/leave
  calculations.
- Employee records remain writable only by Admin/SuperAdmin roles.
- Employee role/profile fields are not treated as authorization claims.
- No client-side rule is used to create Firebase Auth accounts.
- No attempt is made to make SQL mutation/calculation boundaries client-writable.

## Files changed

- `Android/database.rules.json`
- `docs/1100/1100-P_EMPLOYEE_FIREBASE_SECURITY_ROLE_SCOPE_FINAL_AUDIT.md`
- `docs/1100/1100-P_RESULT.md`
