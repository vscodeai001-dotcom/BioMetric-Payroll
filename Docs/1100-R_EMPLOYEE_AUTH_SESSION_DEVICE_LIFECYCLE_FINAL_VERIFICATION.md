# 1100-R Employee Authentication / Session / Device Lifecycle Final Verification

## Scope

Final verification of Firebase-native Employee authentication, legacy mobile token compatibility, single-device enforcement, logout, forced session replacement, Auth revocation, and Employee lifecycle state.

## Hardening applied

`MobileTokenAuthenticationHandler` now validates the Firebase token owner scope against the configured Employee Firebase owner before creating the application principal. Employee sessions are rejected when the Employee projection is deleted, and the claimed Employee ID must match the resolved Employee record.

## Lifecycle contract

- Firebase ID tokens are verified with revocation checking.
- Employee Firebase sessions are required at `employee_sessions/{firebaseUid}`.
- When a device ID is supplied, it must match the active Firebase session device.
- Deleted Employee projections cannot authenticate as active Employees.
- Cross-owner Firebase claims are rejected.
- Employee ID claim/record mismatches are rejected.
- Legacy opaque mobile tokens remain available only as a controlled compatibility path.
- Legacy token device locks remain SQL-backed and are not silently migrated.
- Web Identity single-session enforcement remains authoritative for the existing Web login flow.
- Explicit mobile logout releases the SQL device lock and Firebase `employee_sessions` record.
- Employee Auth deactivation revokes refresh tokens and removes the Firebase session projection.
- Force-replacement keeps the existing security-stamp/device-lock flow; GPS cleanup remains part of the existing lifecycle.

## Deliberate boundary

No attempt was made to make the Web Identity cookie itself a Firebase session. The Web login flow and Android Firebase-native flow remain separate authentication mechanisms while sharing the Employee Firebase projection and lifecycle rules.

## Verification limitations

Static source validation was performed. A full .NET build and live Firebase Authentication integration test were not executed because the required runtime/tooling was unavailable in the execution environment.
