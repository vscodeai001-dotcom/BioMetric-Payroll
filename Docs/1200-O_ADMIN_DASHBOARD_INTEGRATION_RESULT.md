# 1200-O Admin Dashboard Integration

## Scope

Admin/SuperAdmin dashboard integration across the Web and native Android application, with Firebase remaining the realtime transport and owner-scoped SSOT read boundary.

## Changes

1. `FirebaseRealtimeService` now exposes `GetOwnerTrackingLiveAsync(ownerUid)` for owner-scoped live tracking reads.
2. `FirebaseAdminDashboardService` now reads `owners/{ownerUid}/tracking/live` instead of the global `tracking/live` tree for dashboard KPI aggregation.
3. `Admin/Home.razor` refreshes the Firebase-backed dashboard snapshot when the tracking realtime stream changes, keeping `LiveTrackingCount` synchronized.
4. Existing Android Admin dashboard map/KPI architecture is preserved: `SignalRManager` is a Firebase-only compatibility facade and `MainActivity` consumes the Firebase-backed realtime flow.
5. Application-scoped `AdminRealtimeCoordinator` remains the Android realtime invalidation gate.

## Regression protections

- No database schema changes.
- No attendance calculation changes.
- No payroll calculation changes.
- No UI layout redesign.
- No new realtime transport introduced.
- No cross-owner tracking aggregation for the Admin dashboard KPI.
- Existing 1200-K cross-day handling and 1200-L/1200-M GPS paths remain unchanged.

## Static validation

`1200O_admin_dashboard_integration_audit.py`: **9/9 PASS**.

Additional source balance checks:

- `FirebaseRealtimeService.cs`: braces 198/198, parentheses 1039/1039.
- `FirebaseAdminDashboardService.cs`: braces 38/38, parentheses 159/159.
- `Home.razor`: braces 71/71, parentheses 145/145.
- `MainActivity.kt`: braces 313/313, parentheses 710/710.

A full .NET/Android Gradle build was not claimed in this environment.
