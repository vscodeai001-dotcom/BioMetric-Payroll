# 1200-M — Background Tracking → Attendance

## Base

1200-L Geofence / GPS Attendance Integration.

## Objective

Guarantee that background GPS tracking remains a durable attendance input even when
Android is backgrounded, connectivity is intermittent, or the foreground tracking
service is restarted.

## Implemented

- Android continues to capture GPS into the existing Room durable queue before upload.
- Every persisted GPS fix still reaches Firebase through the existing Firebase SSOT path.
- Immediate WorkManager sync is requested after a durable GPS capture, while the
  foreground uploader remains available for low-latency delivery.
- Existing chronological queue draining remains authoritative for reconnects, so
  queued points are replayed in timestamp/row order and each accepted live point is
  evaluated by the server geofence attendance engine.
- Tracking session START is durably queued when the direct Firebase session-start
  write fails.
- Tracking session END is durably queued when the direct Firebase session-end
  write fails. This prevents an offline logout/shift stop from leaving an active
  server compatibility session indefinitely.
- Firebase tracking lifecycle events `SESSION_STARTED` and `SESSION_ENDED` are now
  consumed by the Web compatibility bridge and mapped to the existing
  `GeoLocationService` session lifecycle methods.
- Lifecycle replay is idempotent because the existing GPS session service already
  treats duplicate start/end operations safely.
- Existing automatic geofence IN/OUT logic remains in `GeoLocationService`.
- Existing payroll locks, manual overrides, authoritative biometric/mobile punch
  protection, cross-day shift handling, and attendance calculation formulas are
  unchanged.
- No new attendance calculation engine was added to Android.
- No new database schema or Room migration was introduced.
- Firebase remains the independent realtime transport and SSOT for mobile tracking.

## Background attendance flow

```text
Android background location callback
        ↓
Room durable GPS queue
        ↓
Foreground uploader + WorkManager fallback
        ↓
Firebase tracking/history + tracking/live
        ↓
FirebaseSqliteSyncService
        ↓
GeoLocationService.UpdateGpsSessionAsync()
        ↓
Server-authoritative geofence reconciliation
        ↓
Existing automatic GEOFENCE_AUTO IN / OUT punch logic
        ↓
Daily attendance refresh / realtime UI
```

## Offline logout protection

```text
GPS queue remains local
        ↓
User logs out / tracking stops offline
        ↓
SESSION_ENDED lifecycle event is queued locally
        ↓
Connectivity returns
        ↓
WorkManager syncs lifecycle event first
        ↓
Web closes the old GPS session
        ↓
Queued GPS points cannot resurrect that ended session
```

## Important boundary

Android does not calculate or commit attendance punches itself. The Android app is
responsible for durable GPS capture and Firebase transport. The existing server
`GeoLocationService` remains the attendance authority.

## Validation

- Structural Kotlin/C# checks: PASS
- Room migration required: NO
- Database schema changes: NONE
- Android Gradle compile: NOT RUN because the Gradle wrapper distribution could
  not be downloaded in the offline execution environment.
- Full .NET build: NOT RUN because the required .NET SDK is unavailable.
