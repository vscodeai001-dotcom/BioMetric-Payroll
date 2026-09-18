# 990 - Admin/SuperAdmin Live Tracking Map + Tracking Configuration Parity

## Scope

This pass hardens the Android live tracking map and makes its attendance/geofence configuration display realtime and owner-scoped.

## Access

TrackingMapActivity validates the Firebase runtime session and permits only Admin or SuperAdmin roles. Local role preferences are not treated as the sole authority.

## Realtime map

SignalRManager remains a compatibility facade backed by Firebase Realtime Database. The live location listener is owner-scoped and staff accounts are restricted to their own employee node.

## Status

The map accepts ISO timestamps with or without milliseconds/time-zone suffixes and reports Live (<=60s), Stale (<=5m), or Offline (>5m / invalid timestamp).

## Configuration parity

The map observes AttendancePolicyRepository, which combines the owner-scoped feature settings and company settings. The exact hierarchy is displayed as: Geo-Fencing OFF, Biometric Attendance, Automatic Geofence Punching, or Manual Punch, together with the configured radius.

## Safety

Android does not duplicate payroll or attendance calculations. Configuration is displayed and enforced locally for UI/tracking behavior; existing Web attendance/business calculation boundaries remain unchanged.

## Build note

Static source checks can be performed locally. A full Gradle build requires the configured Gradle distribution to be reachable.
