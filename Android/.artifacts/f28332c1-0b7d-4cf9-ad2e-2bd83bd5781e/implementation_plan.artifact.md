# App Stability and Performance Hardening Plan

This plan addresses the reported app crashes upon re-opening after a task-swipe and the freeze/crash when toggling themes.

## User Review Required

> [!IMPORTANT]
> The app is experiencing ANRs and high CPU usage (100% on main thread) due to overlapping marker animations and heavy activity recreation during theme switching.

## Proposed Changes

### [Stability & Performance]

#### [MODIFY] [MainActivity.kt](file:///E:/Project/Android%20App%20Projects/BioMetric+Payroll/app/src/main/java/com/biometric/app/ui/MainActivity.kt)
- Add a `Job` map to track and cancel overlapping marker animations.
- Ensure `updateAdminMarkers` doesn't restart animations if the position hasn't meaningfully changed.
- Increase the initial delay before map setup to ensure the main thread isn't overwhelmed.

#### [MODIFY] [EmployeeHomeActivity.kt](file:///E:/Project/Android%20App%20Projects/BioMetric+Payroll/app/src/main/java/com/biometric/app/ui/EmployeeHomeActivity.kt)
- Fix the marker animation to prevent multiple concurrent animations for the user marker.
- Add additional safety checks in `onCreate` to handle recreation more gracefully.

#### [MODIFY] [TrackingService.kt](file:///E:/Project/Android%20App%20Projects/BioMetric+Payroll/app/src/main/java/com/biometric/app/domain/location/TrackingService.kt)
- Move `startForegroundSafe()` into `onCreate` to ensure it's called as early as possible.
- In `onStartCommand`, check if the service is already in the foreground before calling `startForegroundSafe()` again.
- Add a `isStarting` flag to prevent redundant initialization logic if `onStartCommand` is called multiple times rapidly.

#### [MODIFY] [ThemeManager.kt](file:///E:/Project/Android%20App%20Projects/BioMetric+Payroll/app/src/main/java/com/biometric/app/util/ThemeManager.kt)
- Add a check in `applyTheme` to skip `setDefaultNightMode` if the desired mode is already the current default mode.

## Verification Plan

### Automated Tests
- Run `app:assembleDebug` to verify no compilation errors.

### Manual Verification
1. Install and open the app.
2. Toggle the theme switcher and verify no freeze or crash occurs.
3. Swipe the app away from recent tabs and verify it re-opens successfully without "Wait or Close" errors.
4. Verify that staff markers on the admin map animate smoothly even with frequent updates.
