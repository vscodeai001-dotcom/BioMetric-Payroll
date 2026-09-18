# 988 - Live Location + Geo-Fence Security Parity

## Scope

This pass secures the Android live-location transport without changing attendance or payroll calculation rules.

## Transport

Android writes owner-scoped tracking data under `owners/{ownerUid}/tracking/{live|history|sessions|events}`. The existing global tracking stream is retained for Web compatibility during migration, but now carries and validates `OwnerUid`.

## Authorization

- Employees can write only their own employee ID under their authenticated owner.
- Admin/SuperAdmin can read tracking for their owner.
- Employees can read only their own tracking node.
- Tracking payloads must contain the authenticated owner UID and matching employee ID.
- Geofence management is Admin/SuperAdmin only and uses the authenticated owner namespace.

## Offline

GPS fixes continue to be durably queued locally before upload. Firebase upload failure does not discard the local event.

## Geofence

Geofence coordinates and radius are validated before persistence. The Android geofence manager now resolves the owner from the persisted mobile session instead of using the Firebase Auth UID as an implicit tenant identifier.

## Compatibility

The Web/Render calculation boundary remains unchanged. Android does not independently calculate attendance punches from tracking data.
