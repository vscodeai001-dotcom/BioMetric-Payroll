# Implementation Plan - Android Admin Parity & Fixes

This plan aims to resolve several critical issues in the Android Admin application, focusing on data parity with the Web version, UI improvements for Employee/Attendance records, and fixing the Map and Settings modules.

## User Review Required

> [!IMPORTANT]
> The "User & Role Management" fix involves changing the Firebase path from owner-scoped to global. Ensure that the Firebase security rules allow Admin/SuperAdmin to read `user_profiles` at the root.

## Proposed Changes

### [Component: Data & Sync]

#### [MODIFY] [FirebaseSyncManager.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/sync/FirebaseSyncManager.kt)
- Add `getGlobalDataFlow<T>(path: String)` to allow reading collections from the root level of the Firebase database.
- This is necessary for `user_profiles` which are stored globally by the Web app.

#### [MODIFY] [UserRepository.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/data/repository/UserRepository.kt)
- Update `observeUsers()` to use `getGlobalDataFlow("user_profiles")`.

---

### [Component: Employee & Attendance UI]

#### [MODIFY] [StaffDetailActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/StaffDetailActivity.kt)
- Enhance the UI to mirror the Web Dashboard's KPI-style summary (Scheduled, Worked, OT, Penalty, etc.).
- Add a detailed "Attendance Log" section that lists each day's punches, status, and calculated hours, similar to the Web's `AttendanceLogTable`.
- Implement a better RecyclerView adapter for the attendance log.

#### [MODIFY] [activity_staff_detail.xml](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/res/layout/activity_staff_detail.xml)
- Update layout to include the new summary header and detailed log list.

---

### [Component: Map UI]

#### [MODIFY] [TrackingMapActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/TrackingMapActivity.kt)
- Increase the spiral offset radius for overlapping markers (from `0.00004` to `0.00015`, approx 15-20 meters) to ensure they are distinct at lower zoom levels.
- Fix the "Full Screen" map toggle logic to ensure constraints are properly updated.

#### [MODIFY] [MainActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/MainActivity.kt)
- Apply the same pronounced spiral offset for the Dashboard live map.

---

### [Component: Real-time Sync]

#### [MODIFY] [RealtimeUiDispatcher.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/sync/RealtimeUiDispatcher.kt)
- Add `StaffActivity` and `TrackingMapActivity` to the registry so they automatically refresh when Firebase reports a change.
- Ensure all relevant refresh methods are included for each screen.

#### [MODIFY] [FirebaseRoomHydrator.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/sync/FirebaseRoomHydrator.kt)
- Ensure all transactional nodes (like `daily_summaries`, `audit_logs`) are being watched with a long-lived listener.
- Cross-check with Web `FirebaseRealtimeService` for any missing synchronization nodes.

## Verification Plan

### Automated Tests
- Run `:app:assembleDebug` to ensure compilation is successful.
- Check logs for any Firebase synchronization errors.

### Manual Verification
- **User Management**: Verify that the list of users now appears in the "User & Role Management" screen on Android.
- **Employee Details**: Verify that the new summary header and attendance log list are visible and match Web data.
- **Map**: Test the "Full Screen" button on the tracking map.
- **Settings**: Verify that changing a feature toggle on Android reflects on the Web (and vice-versa).
- **Sync**: Verify that punches added via the Web portal appear instantly on the Android app's attendance log.
