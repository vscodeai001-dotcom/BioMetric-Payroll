# Walkthrough - Android & Web Parity, Sync, and Map Fixes

This walkthrough summarizes the improvements made to resolve overlapping markers, real-time synchronization issues, and GPS session management.

## Changes Made

### 1. Map Overlap Resolution (Android & Web)
- **Problem**: Employee markers at the same location were overlapping, making it difficult to see individual staff.
- **Solution**:
    - Increased the **spiral fanning radius** on the Android map from `0.00004` to `0.00015` (approx. 15-20 meters).
    - Updated `themeInterop.js` on the Web project to increase the **collision threshold** and **fanning radius** for markers, ensuring distinct visibility even at lower zoom levels.

### 2. Real-time Synchronization & Data Parity
- **Problem**: Changes made on the Web portal (like updating salaries or shifts) were not reflecting instantly on Android.
- **Solution**:
    - **Infrastructure**: Started the `FirebaseRoomHydrator` (database sync) at the application level on startup, ensuring real-time hydration for both Admin and Employee roles.
    - **UI Dispatcher**: Added `StaffActivity`, `LeaveManagementActivity`, `RegularizationActivity`, and others to the `RealtimeUiDispatcher` registry.
    - **Data Integrity**: Fixed critical mapping bugs in `FirebaseRoomHydrator` and `FirebaseEmployeeSelfServiceRepository` where incoming updates were losing fields (salary rates, shift timings, bank details) due to incomplete mappers.

### 3. GPS Session Management
- **Problem**: Admin dashboard sometimes showed "Session Ended" for an employee who was still "Live" on the map.
- **Solution**:
    - **Durable Termination**: In `FirebaseSyncManager.kt`, modified `pushTrackingSessionEnded` to mark the live marker with `State = ENDED` instead of deleting the node.
    - **Idempotency**: Updated the `pushLiveLocation` transaction to check for the `ENDED` state. This prevents late GPS points from an already-ended session from accidentally recreating a "ghost" active marker.

## Verification Results

- **Marker Fanning**: Verified that the larger offset radius is applied to both `MainActivity` and `TrackingMapActivity` on Android, and `themeInterop.js` on Web.
- **Real-time Sync**: Verified that `BiometricApplication` now properly kicks off the background hydration bridge.
- **Data Parity**: Verified that `toEmployee`, `toShop`, and `toAttendance` mappers now include all fields from their respective entities.
- **GPS Safety**: Verified that the `live` node is now marked as `ENDED` rather than nullified, providing a durable block against late GPS fixes.

### 4. Logout Punches & Map Selection Fixes
- **Logout Punches**: Implemented `ProcessManualLogoutPunchAsync` in `GeoLocationService.cs`. This method ensures that employees with an "odd" attendance state (IN without OUT) are automatically punched "OUT" upon manual logout, respecting physical machine priority rules.
- **Map Selection**: Updated the map filtering logic in `themeInterop.js` so that selecting an employee hides all other markers, and starting a journey playback hides all live markers, providing a clean and focused view.

> [!TIP]
> After deploying these changes, users should see immediate updates across all screens without needing to reload or log out. Overlapping markers will now be clearly separated by a few meters for better visibility.
