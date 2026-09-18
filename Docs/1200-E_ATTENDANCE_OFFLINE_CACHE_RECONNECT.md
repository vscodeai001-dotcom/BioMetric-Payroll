# 1200-E Attendance Offline Read Cache + Reconnect Reconciliation

## Scope

Adds an IndexedDB resilience cache for the Attendance Log Viewer. Firebase Realtime Database remains the authoritative Attendance SSOT.

## Cached data

- DailySummary read projection
- AttendancePunch read projection
- Cache key is scoped by business date range and selected employee filter
- Cache is accepted only when saved within the last 7 days

## Online flow

Firebase -> Attendance viewer -> IndexedDB replacement cache

## Firebase read failure

Attendance viewer attempts the matching cache entry. If present and fresh, it renders cached data and shows an explicit cached-data warning. If no usable cache exists, the existing error path remains.

## Reconnect

The existing realtime/online refresh mechanism calls `LoadLogs`. A successful Firebase read clears the cached-state indicator and replaces the cache with the latest authoritative data.

## Mutation boundary

No offline mutation queue was added in this module. Re-Process, correction, leave synchronization, and attendance calculation remain on their existing server/SQL boundaries. This prevents replaying stale attendance mutations from browser storage.

## Security note

IndexedDB is a browser resilience cache, not an authorization boundary. Firebase rules and server authorization remain authoritative. Users should use trusted devices for Admin attendance access.
