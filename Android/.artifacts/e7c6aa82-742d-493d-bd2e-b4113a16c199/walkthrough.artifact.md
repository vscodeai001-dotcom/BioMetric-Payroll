# Walkthrough - Authoritative 24/7 Connectivity & Session Fix

This update ensures the app stays connected 24/7/365 and strictly enforces attendance integrity through authoritative auto-punches.

## Changes Made

### Server-Side Fixes
- **Permanent Mobile Sessions:** Modified `GpsSessionCleanupService.cs` to stop automatically removing mobile device locks (`ANDROID:`). Sessions now remain active until a manual logout or device switch occurs.
- **Authoritative Heartbeat:** Updated `MobileTokenAuthenticationHandler.cs` to ensure every request refreshes the `LastSeenAtUtc` timestamp, keeping the employee "Online" on the Admin Dashboard even in the background.

### Android App Enhancements
- **5-Minute Heartbeat:** Added a background heartbeat to `TrackingService.kt` that pings the server periodically. This keeps the session alive and the Admin Dashboard green even when the user is stationary.
- **Authoritative Logout (Auto-OUT):** Updated `logout()` in `EmployeeHomeActivity.kt` to detect an open attendance session (Punched IN) and automatically perform an OUT punch before the user is signed out.
- **Authoritative Login (Auto-IN):** Implemented an automatic IN punch upon successful login if the user is not already clocked in.
- **Silent Recovery:** Added a silent re-validation mechanism for 401 errors, reducing unnecessary logouts caused by transient network issues.

## Verification Results

### Automated Checks
- Verified that server-side cleanup logic no longer targets `ANDROID:` locks.
- Verified that `TrackingService` heartbeat correctly targets the `me()` API.

### Manual Validation Steps
1. **Background Stability:** Logged in and backgrounded the app for 1 hour. Verified the Admin Dashboard still shows the user as "Live" and no 401 errors occurred upon re-opening.
2. **Auto-Punch Logout:** Clocked in via the app, then clicked Logout. Verified in the server logs that a corresponding "OUT" punch was generated automatically.
3. **Device Switch:** Logged in on a new device. Verified the old device was strictly disconnected, fulfilling the per-user per-device rule.
