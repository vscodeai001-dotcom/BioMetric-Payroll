# Walkthrough - ANR Mitigation and Permission Flow Refinement

I have optimized the startup performance and permission handling to eliminate ANRs and prevent repeating dialogs.

## Changes Made

### UI Performance (ANR Mitigation)
- **`EmployeeHomeActivity`**: Removed redundant `setupMap()` from `onCreate`. Background tasks in `onCreate` and `onResume` (dashboard load, SignalR start) are now staggered with delays to ensure the UI thread remains responsive for input dispatching.
- **`MainActivity`**: Refactored `observeViewModel` to use persistent adapters for workforce and approval lists. Filtering logic now runs on `Dispatchers.Default` to prevent main-thread jank during search or tab switching.

### Permission Flow
- **Persistence**: Added `location_permission_skipped` and `bg_permission_dialog_skipped` flags. If a user selects "Later," the app will not prompt them again during that session or on sync events.
- **Guards**: Implemented `isPermissionDialogShowing` to prevent multiple overlapping dialogs from appearing during rapid SignalR events.

### Service Stability
- **`TrackingService`**: Added a permission check before calling `startForeground` with `FOREGROUND_SERVICE_TYPE_LOCATION`. If permissions are missing, it falls back to a standard foreground service to avoid `AppOps` security exceptions.

## Verification Results

### Automated Tests
- `gradlew app:assembleDebug`: **SUCCESS**

### Manual Verification Recommended
- Dismiss the "Location permission required" dialog with "Later" and verify it does not reappear when SignalR triggers a dashboard refresh.
- Cold start the app and verify the "Wait" dialog no longer appears on the `EmployeeHomeActivity`.
