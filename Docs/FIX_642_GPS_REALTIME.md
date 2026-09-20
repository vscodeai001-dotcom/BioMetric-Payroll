# 642 GPS / Realtime Fix

Applied to the current 642 base.

## Fixed
1. Android Firebase GPS history no longer gets written before an old/ended session is validated.
2. Offline GPS points belonging to an already-ended session are retained in immutable history without resurrecting the live marker.
3. Live GPS transaction is idempotent for the same ClientEventId, so a retry after a history-write failure can complete safely.
4. A late logout/end event cannot mark a newer Firebase live session as ENDED.
5. Web Firebase history events are always attendance-neutral. Replayed history cannot create or alter attendance.
6. Web history startup/reconnect hydration uses the same idempotent history pipeline instead of trying to map Android ClientEventId onto the SQL identity Id.
7. Web GPS history is deduplicated using employee + session + captured timestamp + coordinates, preventing Firebase reconnect snapshots from creating duplicate timeline points.
8. The current SignalR-optional `attendance-refresh.js` is included from the 642 base so missing SignalR CDN does not block Firebase realtime startup.

## Existing behavior preserved
- No database schema redesign.
- Existing attendance/geofence calculation engine remains authoritative.
- Existing Firebase SSOT architecture remains.
- Existing Android UI/Web UI structure remains.

## Verification
- Kotlin source brace/parenthesis balance checked.
- C# source brace/parenthesis balance checked.
- Full Android Gradle compilation could not be run in this environment because Gradle 9.5.0 was not cached and external network access is unavailable.
