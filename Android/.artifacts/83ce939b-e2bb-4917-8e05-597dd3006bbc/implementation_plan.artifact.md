# Implementation Plan - Eliminating ANRs and Permission Nagging

This plan addresses the startup ANRs, redundant initializations, and the repeating "Silent Background Tracking" permission dialog.

## Proposed Changes

### UI & Startup Optimization
- **[MODIFY] [EmployeeHomeActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/app/src/main/java/com/biometric/app/ui/EmployeeHomeActivity.kt)**
    - Remove the redundant `setupMap()` call from the main `onCreate` flow; let the staggered initialization handle it.
    - Implement a `isPermissionDialogShowing` guard to prevent multiple dialog instances.
    - Move `loadDashboard()` and `signalR.start()` in `onResume` to a small delayed coroutine to ensure the UI is fully interactive before background tasks start.
    - Audit `requestLocationPermission` to ensure the `bg_permission_dialog_skipped` flag is checked at the very beginning of the permission flow.
    - Add a persistence flag for the `showLocationPermissionMessage` dialog to prevent it from nagging if the user explicitly chose "Later" for the base location permission.

### Service & Permission Stability
- **[MODIFY] [TrackingService.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/app/src/main/java/com/biometric/app/domain/location/TrackingService.kt)**
    - Add a safety check in `onStartCommand` to verify permissions before calling `startForeground` with the location type, providing a fallback type if permissions are missing to avoid `AppOps` security exceptions.

### Global Lifecycle Tuning
- **[MODIFY] [MainActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/app/src/main/java/com/biometric/app/ui/MainActivity.kt)**
    - Optimize `observeViewModel` to ensure it doesn't block the main thread with heavy initial data processing.

## Verification Plan

### Automated Tests
- Run `app:assembleDebug` to ensure no regressions in build logic.
- (Manual) Verify that dismissing the "Background Tracking" dialog with "Later" prevents it from reappearing on subsequent SignalR syncs or app resumes.

### Manual Verification
- Cold start the app and monitor for the "Wait" dialog/ANR.
- Toggle SignalR events from the server to ensure `loadDashboard` doesn't trigger unexpected UI prompts.
- Check Logcat for `AppOps` or `ForegroundServiceStartNotAllowedException` during background transitions.
