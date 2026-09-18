# Android ↔ Web Mirror Contract

## Attendance source priority

1. Physical biometric attendance is authoritative.
2. Automatic geofence attendance is fallback/reconciliation.
3. Explicit mobile/manual attendance is retained as an application attendance source.

## Geo-Fencing master switch

When Geo-Fencing is OFF:
- GPS/geofence attendance is disabled.
- Dual Attendance is disabled.
- Automatic Geofence Punching is disabled.
- Physical biometric attendance remains active.
- Android stops its location foreground service.

## Manual Employee Punch visibility

The manual PUNCH IN / OUT action is visible only when:
- biometric attendance is not active, and
- automatic geofence punching is not active.

It is enabled only when:
- a valid location exists,
- the office is configured,
- the configured radius is greater than zero,
- distance <= the exact configured radius,
- no punch operation is in progress, and
- no location refresh is in progress.

There is no hidden Android-only +1m or hysteresis tolerance.

## Automatic geofence reconciliation

For every valid GPS fix while Geo-Fencing and Automatic Geofence Punching are enabled:

- GPS INSIDE + attendance currently OUT => automatic IN.
- GPS OUTSIDE + attendance currently IN => automatic OUT.
- GPS INSIDE + attendance currently IN => no duplicate IN.
- GPS OUTSIDE + attendance currently OUT => no duplicate OUT.

The attendance state is based on chronological punch parity. A null/stale previous GPS state does not suppress reconciliation.

## Session contract

Android publishes:

`tracking/sessions/{employeeId}/{sessionId}`

before publishing the live GPS stream. Logout/end publishes `EndedAtUtc` and `EndReason` for the same session.

This is required because the existing Web GeoLocationService only accepts live GPS updates for a known active GPS session.

## Architecture rule

Android does not implement a second competing payroll/attendance calculation engine. The existing Web/SQL compatibility engine remains the business-rule authority during phased migration. Firebase is the shared realtime transport/read model, and Android Room is the offline/cache projection.
