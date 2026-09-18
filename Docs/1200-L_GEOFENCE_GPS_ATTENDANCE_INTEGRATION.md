# 1200-L Geofence / GPS Attendance Integration

## Scope

Built directly from **1200-K Midnight / Cross-Day Shift Handling**.

The existing Web/Android GPS architecture already contained geofence configuration,
GPS sessions, Firebase live/history transport, automatic geofence attendance, and
location history. The integration audit found one critical boundary gap:

> Android Firebase live GPS updates were updating the admin live-location store,
but the Firebase live-event compatibility handler was not feeding those updates
through `GeoLocationService.UpdateGpsSessionAsync`. Therefore an Android Firebase
GPS fix could appear on the map without driving the existing automatic geofence
attendance reconciliation engine.

## Implemented

- Firebase live GPS events now enter `GeoLocationService.UpdateGpsSessionAsync`.
- The server recalculates distance from the current Admin-configured office
  coordinates and radius instead of trusting client-supplied geofence values.
- Invalid Firebase coordinates are rejected before attendance/location processing.
- Existing GPS session lifecycle and ordering protection remain authoritative.
- Existing automatic geofence attendance reconciliation is reused unchanged.
- Existing biometric/mobile punch priority and conflict protection remain unchanged.
- Existing manual attendance override and payroll-lock behavior remain unchanged.
- Existing 1200-K overnight/cross-day attendance behavior remains the calculation authority.
- `LiveLocationStore` is no longer written twice for Firebase live events. The
  authoritative `UpdateGpsSessionAsync` path performs the live-store update after
  session/order validation.
- Android Firebase remains owner-scoped and continues to use immutable history
  event IDs plus sequence-protected live state.
- No database schema changes.
- No UI/layout changes.
- No Firestore introduced.

## End-to-end flow

```text
Android GPS fix
    |
    v
Firebase owners/{ownerUid}/tracking/history + tracking/live
    |
    v
Firebase realtime compatibility stream
    |
    v
Server recalculates office distance/radius
    |
    v
GeoLocationService.UpdateGpsSessionAsync
    |
    +--> GPS session + LiveLocationStore
    |
    +--> Automatic geofence reconciliation (when enabled)
    |       |
    |       +--> AttendanceLog IN/OUT
    |       +--> GeoPunchAudit
    |       +--> attendance refresh
    |
    v
Admin Web / Android realtime state
```

## Important authority rule

Firebase is the transport/realtime SSOT for the mobile GPS stream, but the existing
`GeoLocationService` remains the attendance decision authority. Client-provided
`distance`, `radius`, and `within` values are not trusted for attendance decisions.

## 1200-K compatibility

For an overnight shift such as `22:00 -> 06:00`, the geofence-generated attendance
punch still enters the existing attendance calculation path. 1200-K remains
responsible for assigning cross-midnight punches to the original ShiftDate.

## Validation

- 1200-L static integration audit: PASS
- C# brace balance: PASS
- C# parenthesis balance: PASS
- Database schema migration: NOT REQUIRED
- .NET full build: NOT RUN, .NET SDK unavailable in the execution environment
