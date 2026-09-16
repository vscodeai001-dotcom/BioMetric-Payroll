# Offline Continuous Telemetry and Attendance Radius Enforcement

Implement a robust offline tracking system that ensures continuous GPS telemetry, local geofence validation (100m radius), and seamless synchronization with Firebase upon reconnection.

## User Review Required

> [!IMPORTANT]
> - **Geofence Caching**: Geofences will now be stored in the local Room database to allow offline validation.
> - **Breach Logging**: Boundary breaches will be logged as `OfflineTrackingEvent` entries in the local database with type `BOUNDARY_BREACH`.
> - **Battery Impact**: The high-priority native tracking loop (`TrackingService`) with a partial wake lock is designed for 24/7 operation. This may impact battery life, as requested.

## Proposed Changes

### Database Layer

#### [MODIFY] [GeofenceLocation.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/data/entity/GeofenceLocation.kt)
- Annotate the class with `@Entity(tableName = "geofences")`.
- Mark `id` as `@PrimaryKey`.

#### [NEW] [GeofenceDao.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/data/dao/GeofenceDao.kt)
- Create `GeofenceDao` with methods: `insertAll`, `getAll`, `clearAll`.

#### [MODIFY] [AppLocalDatabase.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/data/AppLocalDatabase.kt)
- Add `GeofenceLocation` to the `entities` list.
- Add `abstract fun geofenceDao(): GeofenceDao`.
- Increment database version and add migration if necessary (or rely on `fallbackToDestructiveMigration` if acceptable, but better to add a migration for `geofences` table).

---

### UI Layer

#### [MODIFY] [GeofenceManagerActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/GeofenceManagerActivity.kt)
- Inject `GeofenceDao`.
- In `listenToGeofences`, update the local Room cache whenever Firebase data changes.

---

### Domain Layer (Tracking & Geofencing)

#### [MODIFY] [TrackingService.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/domain/location/TrackingService.kt)
- Inject `GeofenceDao` and `GeofenceManager`.
- In `handleLocationUpdate`, fetch cached geofences and call `geofenceManager.validatePunchLocation`.
- If `isInside` is false (and geofences are defined), log a `BOUNDARY_BREACH` event to `OfflineTrackingEventDao`.
- Ensure the tracking loop remains high-priority and persistent.

#### [MODIFY] [GeofenceManager.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/domain/location/GeofenceManager.kt)
- Ensure Haversine formula is correctly used (already present).

---

### Sync Layer

#### [MODIFY] [OfflineSyncWorker.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/domain/location/OfflineSyncWorker.kt)
- Ensure it also syncs `OfflineTrackingEvent` logs to Firebase if they haven't been synced. (Need to check if `OfflineTrackingEvent` has a sync state).

---

## Verification Plan

### Automated Tests
- Unit tests for `GeofenceManager#calculateDistance` to verify Haversine accuracy.
- Room database tests to verify geofence caching and event logging.

### Manual Verification
- Deploy to device.
- Add a geofence around current location.
- Turn off internet.
- Move outside the 100m radius.
- Verify log entry in `offline_tracking_events` table (using Database Inspector).
- Turn on internet.
- Verify logs and coordinates are synced to Firebase.
- Verify Admin dashboard updates in real-time.
