# Web → Android parity implementation status

Updated: 2026-09-22

## Completed foundation
- Web route inventory audited against Android native activities.
- Android is treated as a native mobile presentation of the Web application, not a web-layout copy.
- Firebase/SSOT remains the shared realtime source.
- No manual refresh/reload/logout-login is required for normal realtime convergence.
- Room remains temporary GPS failed-delivery queue/cache only.
- Generic raw Firebase/JSON viewer has been removed from the parity navigation.
- Parity hub now lists all audited Web Admin modules, including FBP Components, FBP Approval, Year-End Summary, Company Attendance Report, Location Stays and Attendance Event Monitoring.

## Next-pass implementation completed
1. FBP Components: dedicated native Web-aligned CRUD screen; SuperAdmin-only editing; existing Web API remains the business-rule boundary; Firebase projection drives realtime display.
2. FBP Declaration Approval: dedicated native approval/rejection screen; approval/rejection endpoints preserve the existing Web semantics and publish the resulting declaration projection to Firebase.
3. Year-End Summary: dedicated native annual compliance screen reading the Firebase SSOT projection; Web remains the calculation authority.
4. Location Stays: dedicated native stay/visit screen using the existing GPS history and the Web's 10 m / 10 minute detection rule.
5. Attendance Event Monitoring: dedicated native realtime AUTH_SESSION event monitor from the existing audit-log SSOT projection.

## Remaining verification
6. Every route: verify Android CRUD, validation, permissions, calculations and approval conditions against the corresponding Web implementation.
7. Cross-platform realtime acceptance test for every module: Web→SSOT→Android and Android→SSOT→Web without refresh/reload/logout-login.
8. Full Android Gradle build/regression test in an environment with the project's required Gradle/Android dependencies.

## Do not reintroduce
- Generic Firebase JSON/table viewer as a substitute for a Web screen.
- Android-only SSOT.
- Android-only payroll/attendance calculations.
- Unrelated Android-only admin screens.

## 2026-09-22 Navigation + Realtime Warning Fix
- Admin navigation now exposes the Web-parity modules directly from the MainActivity toolbar via `Admin Modules` for Admin/SuperAdmin users.
- Web-parity modules are grouped into PAYROLL, ATTENDANCE, ADMIN & SETTINGS, LOCATION, and REPORTS & INSIGHTS sections.
- Firebase RTDB indexes added for `tracking/history/$employeeId.Timestamp` and `owner_events/$ownerUid.timestamp` to prevent unindexed query failures/warnings.
- Android `MainViewModel` no longer logs expected coroutine cancellation as `Workforce recalculation failed`.
- Web local SQLite compatibility DbContext now suppresses only EF Core `SchemaConfiguredWarning` for SQLite schema metadata.
