# Implementation Plan - Authoritative 24/7 Connectivity & Session Fix

This plan addresses the "Failed to load 401" errors and "Inactivity" issues by making the mobile session authoritative and removing all automatic timeouts that cause disconnections.

## User Review Required

> [!IMPORTANT]
> **Authoritative Mode:** I am disabling the 10-minute "abandoned session" cleanup for mobile devices. Once a mobile device logs in, the session will remain **PERMANENT** until the user explicitly logs out or logs in from another device. This ensures the app never shows "Inactive" or "Disconnected" even after a long time.

## Proposed Changes

### [Server] Payroll.AttendanceService

#### [MODIFY] [appsettings.json](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetricPayRoll_Web/Payroll.AttendanceService/appsettings.json)
- Increase `MobileSessionLeaseSeconds` to `31536000` (1 Year) as a safety buffer, though logic will be changed to ignore it for mobile.

#### [MODIFY] [GpsSessionCleanupService.cs](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetricPayRoll_Web/Payroll.AttendanceService/Services/GpsSessionCleanupService.cs)
- **Disable** the removal of `EmployeeDeviceLocks` that start with `ANDROID:` during the cleanup loop.
- Only allow explicit Logouts or Force Replacements to remove these locks.

### [Server] Payroll.Web

#### [MODIFY] [MobileTokenAuthenticationHandler.cs](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetricPayRoll_Web/Payroll.Web/Security/MobileTokenAuthenticationHandler.cs)
- Ensure every API call (Dashboard, Profile, etc.) refreshes the `LastSeenAtUtc` timestamp so the Admin Dashboard always sees the user as "Online" as long as the app is open.

---

### [Android] Mobile App

#### [MODIFY] [TrackingService.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/app/src/main/java/com/biometric/app/domain/location/TrackingService.kt)
- **Heartbeat Mechanism:** Add a 5-minute periodic "Ping" to the server. If no GPS update has been sent for 5 minutes, the app will send a minimal heartbeat to keep the session alive and the Admin dashboard green.
- **Aggressive Recovery:** If a 401 error is received, the app will immediately attempt to re-validate the token using the `me()` API before failing, handling transient session issues silently.

#### [MODIFY] [EmployeeHomeActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/app/src/main/java/com/biometric/app/ui/EmployeeHomeActivity.kt)
- Increase the SignalR reconnection attempts and ensure the "Live" status is maintained visually.

## Verification Plan

### Manual Verification
1. Deploy updated server.
2. Log in to the Android app.
3. Turn off GPS and leave the app in the background for 30 minutes.
4. Verify on the **Admin Dashboard** that the employee still shows as "Active/Live".
5. Re-open the app and verify all screens (Profile, Salary, Leaves) load instantly without 401 errors.
6. Force-stop the app and verify the `TrackingService` restarts it within 1 minute (Recovery mechanism).
