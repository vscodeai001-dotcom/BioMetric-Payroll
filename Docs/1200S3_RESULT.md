# 1200-S3 Realtime / Android ANR Final Repair

## What the supplied log proves

1. `MainActivity` hit an Android ANR: input dispatch timed out for 5001 ms.
2. During the ANR window `com.biometric.app` consumed about 98% CPU.
3. HWUI repeatedly emitted `Image decoding logging dropped`, matching excessive
   map/tile rendering pressure.
4. Firebase persistence was configured after realtime coordinators had already
   started touching Firebase, producing `setPersistenceEnabled()` failure.
5. The app repeatedly failed to deserialize legacy `AuditLog` rows because
   Firebase supplied `HashMap` values where the entity expects strings.
6. The captured Web deployment still contains the old `Number` vs `String`
   employeeId failure in `FirebaseAdminDashboardService`, while the supplied S2
   source already contains the robust parser. That means the deployed Web build
   must be replaced, not merely restarted.

## S3 fixes

### Android
- Firebase persistence/cache configuration now runs before realtime coordinator
  startup.
- Full `audit_logs` eager `keepSynced(true)` was removed. Audit logs are read
  when requested instead of forcing the complete collection into the realtime
  cache during Admin startup.
- AuditLog decoding now handles scalar legacy values safely and skips malformed
  rows without stack-trace flooding.
- `LocationChanged` no longer causes a second immediate map render. The
  `liveLocations` StateFlow is already the authoritative realtime stream and is
  coalesced by the existing collector.
- Admin map marker animation was removed. Each GPS fix is applied once. The
  former 1.2-second ValueAnimator continuously invalidated two OSMDroid maps.
- Duplicate live snapshots are ignored using a visible-state render signature.
- OSRM route requests are now throttled by request time and cannot be
  repeatedly cancelled/restarted by a rapid GPS stream.

### Web
The S2 source's Number/String employeeId handling and GPS-refresh isolation are
retained. The Web server must be deployed from this source so the runtime no
longer executes the old `Int()` implementation.

## Preserved
- Tenant/owner isolation
- Saved employee provisioning
- Payroll rules
- Attendance rules
- Shift rules
- Existing screen structure/layout
- Firebase data model

## Build
A full Android Gradle build was not claimed because Gradle 9.5.0 is unavailable
in this environment.
