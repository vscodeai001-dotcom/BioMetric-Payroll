# Stability Fix 2026-09-22

## Scope

This pass fixes the current Web/Android GPS and Firebase stability issues without changing payroll calculations, attendance calculation rules, database schema, or the Firebase SSOT architecture.

## 1. Automatic geofence punch state machine

Automatic GPS punches are now generated only on a radius state transition:

- OUTSIDE -> INSIDE = one automatic IN
- INSIDE -> OUTSIDE = one automatic OUT
- INSIDE -> INSIDE = no punch
- OUTSIDE -> OUTSIDE = no punch
- Initial OUTSIDE state = state initialization only, no OUT punch
- Initial INSIDE state can create the first IN when attendance is still OUT

A failed SQLite write does not advance the GPS session geofence state, allowing the next GPS fix to retry the same genuine transition. This prevents repeated IN records caused by treating every inside GPS fix as a new attendance event.

## 2. SQLite concurrency protection

Web and AttendanceService bootstrap the shared compatibility database with:

- WAL journal mode
- NORMAL synchronous mode
- 10-second SQLite busy timeout

GeoLocationService SaveChanges operations now retry transient SQLITE_BUSY/SQLITE_LOCKED errors with bounded backoff.

## 3. Firebase dashboard read reduction

Admin dashboard KPI refreshes no longer download the complete attendance, shift schedule, and daily summary collections for every realtime event.

- Attendance is queried for today's `checkInTime` range.
- Shift schedules are queried for today's `shiftDate`.
- Daily summaries are queried for the current month's `shiftDate` range.
- Existing employee/advance/payroll/tracking data contracts remain unchanged.

## 4. Dashboard refresh storm protection

Firebase dashboard child events are coalesced with a 750 ms debounce and a 2-second minimum KPI refresh interval. High-frequency GPS events no longer trigger a complete Admin KPI snapshot reload.

## 5. Live tracking contract

The owner-scoped Firebase path remains authoritative:

`owners/{ownerUid}/tracking/live/{employeeId}`

No SQL fallback was introduced for the live map, preserving Firebase as the cross-platform realtime SSOT.

## Validation note

The execution environment used to prepare this package does not contain the `dotnet` SDK, so a local `dotnet build` could not be executed here. The changed C# files were inspected after modification. Build should be run in the user's Visual Studio/CI environment before deployment.
