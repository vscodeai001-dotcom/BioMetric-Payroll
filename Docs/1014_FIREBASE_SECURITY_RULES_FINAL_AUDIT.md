# 1014 Firebase Security Rules Final Audit

## Scope

This module audits the Firebase Realtime Database and legacy Firestore rules against the application's three roles: SuperAdmin, Admin, and Employee/Staff. Firebase RTDB remains the application SSOT. No database schema or screen layout is changed.

## Critical fixes applied

1. Removed the owner-level `$adminTable` catch-all. Unknown `owners/{ownerUid}/{path}` locations are now denied instead of being implicitly Admin-readable/writable.
2. Added explicit rules for `tax_declarations`, `fbp_declarations`, `salary_snapshots`, `daily_summaries`, `salary_payments`, `shop_closed_days`, `company_settings`, `tracking_scopes`, and related existing paths.
3. Added query-scoped employee reads for sensitive employee collections.
4. Prevented employee writes from changing the target employee/staff identifier on existing records.
5. Employee-created leave/regularization/resignation/tax/FBP records are restricted to the expected initial Pending/Draft state.
6. Attendance/punch employee writes are restricted to the authenticated employee's own records.
7. Audit logs are append-only from clients and must identify the authenticated writer.
8. Employee session validation now binds `uid`, `ownerUid`, and `employeeId` to the authentication claims.
9. Firestore no longer exposes public `userProfiles` or a signed-in catch-all collection rule. Only the legacy profile mirror and route replay paths remain explicitly authorized.

## Role matrix

| Path | SuperAdmin | Admin | Employee |
|---|---|---|---|
| employees | read/write owner scope | read/write owner scope | read own |
| attendance | read/write | read/write | read own, write own record |
| attendance_punches | read/write | read/write | read own, create/update own punch only |
| leave_requests | read/write | read/write | read own, create Pending only |
| advances | read/write | read/write | read own |
| bonuses | read/write | read/write | read own |
| tax | read/write | read/write | read own, create Pending only |
| FBP | read/write | read/write | read own, create Draft only |
| payroll_history | read | read | read own |
| payroll_previews | read | read | denied |
| payroll_finalization | read | read | denied |
| year_end_summaries | read | read | denied |
| tracking/live | read | read | own live write/read |
| tracking/history | read | read | own append-only history |
| tracking/sessions | read | read | own session write |
| tracking_scopes | read/write | read/write | own record read |
| audit_logs | read | read | create own audit entry |
| recycle_bin | read/write | denied | denied |
| user_profiles | owner/admin read | owner/admin read | own read/update without role change |
| sessions | own employee session | not granted | own employee session |

## Important implementation note

The employee self-service repository previously fetched several complete owner collections and filtered them locally. That pattern is not sufficient for least-privilege RTDB rules. Module 1014 changes those reads to Firebase `orderByChild(...).equalTo(...)` queries for employee-owned data, matching the rules without changing the displayed UI or business calculations.

## Firestore audit

The application contains legacy Firestore use for the profile mirror and route replay. Public profile reads/listing and the generic signed-in read/write catch-all were removed. All unspecified Firestore paths are denied.

## Validation performed

- RTDB rules parsed as valid JSON.
- Required security paths explicitly present.
- No `$adminTable` catch-all remains.
- Firestore rules contain no public `allow read: if true` and no generic `{collection}/{doc}` grant.
- Repository query changes are syntax-checked structurally.
- Full Gradle build/deployed Firebase rules were not performed in this environment, so live Firebase emulator/deployment verification remains a release-step check in module 1019.

## Non-goals

- No UI/layout changes.
- No payroll calculation rewrite.
- No Neon/Render dependency introduced.
- No Firebase Admin SDK added to Android.
- No data migration or destructive deletion.
