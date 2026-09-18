# 989 - Attendance Policy Realtime Parity

## Scope

This stage synchronizes the attendance feature hierarchy and office geofence configuration to Android in realtime. It does not move attendance/payroll calculation logic into Android.

## Authoritative hierarchy

```text
Geo-Fencing OFF
  -> Dual Attendance OFF
  -> Automatic Geofence Punching OFF

Geo-Fencing ON + Dual Attendance ON
  -> biometric attendance active

Geo-Fencing ON + Automatic Geofence Punching ON
  -> automatic geofence attendance active

Geo-Fencing ON + both higher-priority modes OFF
  -> manual punch is available when inside radius
```

The Android `EmployeeAttendanceStateMachine` remains a presentation/eligibility mirror. Automatic punch creation is still performed by the existing Web attendance engine through the Firebase tracking/session bridge, avoiding a second calculation engine.

## Realtime sources

Android listens to:

- `owners/{ownerUid}/feature_settings/1`
- `owners/{ownerUid}/company_settings/1`

The policy listener accepts canonical camelCase fields plus the legacy snake_case/uppercase names already supported by the Employee dashboard repository.

## Runtime behavior

- A Web/Admin setting change updates the Employee Home policy without waiting for a dashboard refresh.
- Disabling Geo-Fencing immediately stops the Android tracking service.
- Dual Attendance and Automatic Geofence Punching cannot remain active when Geo-Fencing is off.
- The manual punch button is recalculated from the same three-feature hierarchy.
- The tracking service independently observes the policy so an already-running background service also stops when Geo-Fencing is disabled.
- `FirebaseSyncManager` keeps `feature_settings` and `company_settings` synchronized for offline cache support.

## Tracking cadence

The existing GPS cadence is intentionally unchanged in this stage. No Android-only interval or shift-based attendance calculation was introduced because there is no existing authoritative tracking-interval setting to mirror.

## Safety boundary

Android never generates an automatic geofence punch merely because it detects entering/leaving the radius. It publishes the GPS/session evidence and leaves automatic attendance reconciliation to the existing Web engine. This prevents duplicate or divergent attendance calculations.
