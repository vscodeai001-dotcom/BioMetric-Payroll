# Fix Admin Login Freeze and SmartBackground Restrictions

Address the application freeze during Admin login caused by aggressive data synchronization and background worker scheduling, which also triggers system-level "SmartBackground" restrictions on certain devices (vivo/Oppo/iQOO).

## User Review Required

> [!IMPORTANT]
> - **Sync Optimization**: The `FirebaseRoomHydrator` currently attempts to download and sync the entire owner database (Employees, Attendance, Punches, etc.) into the local Room cache immediately upon login. For Admins, this can be thousands of records, causing CPU saturation and UI freezes.
> - **Flow Throttling**: The `MainViewModel` is recalculating complex dashboard KPIs (Workforce count, OT, Payroll variance) for every single record synced. We will add a throttle (debounce) to prevent redundant calculations during initial sync.
> - **Startup Load**: Background workers scheduled in `Application.onCreate` will be staggered or moved to a less aggressive schedule to avoid triggering OEM-specific background restrictions.

## Proposed Changes

### 1. Synchronization Optimization

#### [MODIFY] [FirebaseSyncManager.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/sync/FirebaseSyncManager.kt)
- Remove `keepSynced(true)` from large transactional tables (`attendance`, `attendance_punches`, `audit_logs`).
- Keep `keepSynced(true)` only for essential small configuration nodes (`feature_settings`, `company_settings`, `shops`).
- This prevents the Firebase SDK from automatically downloading the entire history of these tables in the background critical path.

#### [MODIFY] [FirebaseRoomHydrator.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/sync/FirebaseRoomHydrator.kt)
- Wrap the initial reconciliation logic (which uses `addListenerForSingleValueEvent`) in a small delay or lower priority dispatcher.
- Add logging to track hydration progress.

### 2. ViewModel & UI Performance

#### [MODIFY] [MainViewModel.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/viewmodel/MainViewModel.kt)
- Add a `debounce(1000.milliseconds)` to the combined flow in `recalculateWorkforce`.
- This ensures that during a bulk sync of 1000 records, the dashboard only recalculates once every second instead of 1000 times.

#### [MODIFY] [MainActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/MainActivity.kt)
- Increase delays for "Heavier" UI components (Maps, Search) to allow the basic UI to render and settle first.

### 3. Application Startup & Background Logic

#### [MODIFY] [BiometricApplication.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/BiometricApplication.kt)
- Delay the start of `adminRealtimeCoordinator` and `firebaseReconnectCoordinator` by 2-3 seconds using a coroutine.
- Change `ExistingPeriodicWorkPolicy.REPLACE` to `ExistingPeriodicWorkPolicy.KEEP` for workers where possible to avoid unnecessary database writes in `WorkManager` during startup.
- Move OSMDroid initialization to a background thread to prevent blocking the Main thread.

## Verification Plan

### Manual Verification
- **Admin Login**: Perform Admin login on a device and verify the screen transitions smoothly to `MainActivity` without freezing.
- **KPI Accuracy**: Verify dashboard stats (Present Today, Workforce count) still update correctly after the sync settles.
- **Logcat Monitoring**: Check logs for any remaining `SmartBackground restriction` errors or "JobServiceContext: Binding died" warnings.
- **Map Responsiveness**: Ensure the live maps in `MainActivity` are still responsive and show employee markers after the initial sync completes.
