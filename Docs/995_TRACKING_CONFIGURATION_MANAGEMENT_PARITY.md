# 995 - Tracking Configuration Management Parity

## Goal
Make Firebase the authoritative realtime source for Android tracking mode/configuration while retaining a local cache only for startup/offline continuity.

## Configuration path
`owners/{ownerUid}/tracking_configuration`

Fields:
- `mode`: `24/7`, `SHIFT`, or `CUSTOM`
- `customStart`: `HH:mm`
- `customEnd`: `HH:mm`
- `intervalSeconds`: 15..3600
- `enabled`: boolean
- `updatedBy`
- `updatedAt`

## Runtime flow
Admin/SuperAdmin settings -> Firebase -> TrackingConfigurationRepository listener -> tracking_prefs cache -> TrackingWindowResolver/TrackingService.

A configuration change triggers immediate reevaluation of the running tracking service. The interval is applied on the next location request/start.

## Security
The Firebase owner path is explicit and read/write access is restricted to authenticated Admin/SuperAdmin roles. Employee tracking data remains separately owner/employee scoped.

The Android settings screen additionally checks the existing application permission model: SuperAdmin may edit; Admin requires `adminCanEditSettings`.

## Compatibility
Default mode remains `24/7`; existing payroll, attendance calculations, geofence rules, and shift calculations are not duplicated or rewritten.
