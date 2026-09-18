# 982 - Firebase Auth / Role / Employee Session Security

## Security contract

Firebase Authentication is the identity source. The Android `auth_prefs` and `mobile_session` values are navigation/cache state only and are not treated as authority for a role or tenant.

At cold start, `FirebaseAuthSecurityGate` refreshes the Firebase ID token and validates:

- supported application role (`SUPER_ADMIN`, `ADMIN`, or `STAFF`)
- `owner_uid` tenant claim
- `employee_id` for Employee accounts
- active `employee_sessions/{firebaseUid}` ownership for Employee single-device sessions

If the Firebase session has been revoked, the role/tenant claims no longer match, or another device replaced the Employee session, the local session is cleared and the user is returned to Login.

## Employee single-device rule

`FirebaseEmployeeSessionManager` owns the atomic session record. A second device cannot replace an existing device unless the explicit Replace & Login flow is selected. Cold-start validation also detects a session replaced by another device.

## Logout / account switching

The Login screen's explicit Switch Account / Sign Out action now releases the Employee Firebase session when applicable, signs out Firebase Authentication, then clears local session state.

## Network credential safety

Retrofit HTTP logging is now `BASIC` only for debug builds and disabled for release builds. This prevents release logs from exposing Firebase bearer tokens, passwords, or employee request bodies.

## Realtime Database profile rules

The root `user_profiles` compatibility node is explicitly secured. Signed-in users can read their own profile; Admin/SuperAdmin can manage profiles. Self-service writes must preserve the existing role and owner binding, preventing an Employee client from self-promoting or changing tenant ownership.

The owner-scoped `owners/{ownerUid}/...` namespace remains the shared cross-platform data namespace.

## Verification

Static checks completed for modified Kotlin files: balanced braces/parentheses.

A Gradle compile was attempted with `./gradlew --offline :app:compileDebugKotlin --no-daemon`, but the wrapper attempted to download Gradle 9.5.0 and the environment could not resolve `services.gradle.org` (`UnknownHostException`). Therefore no successful build is claimed in this package.
