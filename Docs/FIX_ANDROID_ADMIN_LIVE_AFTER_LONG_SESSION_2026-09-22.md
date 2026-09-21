# Android Admin Live Location Long-Session Recovery Fix

## Symptom

After an Android Admin dashboard remains open for roughly an hour, the Web Admin can still show an employee as live while Android Admin shows the same employee offline. Logging out and logging back in restores the live employee.

## Root cause found in the current Android realtime manager

`SignalRManager` had a `Firebase Realtime Database` `ValueEventListener` for the owner-scoped `tracking/live` node. Its `onCancelled()` handler explicitly ignored `PERMISSION_DENIED`.

A long-lived Firebase listener can be cancelled when the credentials/token used by that listener are no longer accepted. Ignoring that cancellation leaves the existing `liveLocations` StateFlow with old data. The Android UI then eventually classifies the old GPS timestamp as Offline. A manual logout/login recreated the Firebase listener, which is why the problem appeared to disappear.

## Fix

1. Never permanently ignore a live-location listener cancellation, including `PERMISSION_DENIED`.
2. Refresh the Firebase ID token automatically.
3. Rebuild the owner-scoped live listener automatically without logout/login.
4. Refresh the token every 30 minutes instead of waiting close to the normal token lifetime.
5. Perform an immediate canonical live read after a successful proactive token refresh.
6. Cancel the recovery job cleanly during normal logout/stop.

## Scope

- Firebase remains the SSOT.
- No Room-first change.
- No GPS tracking interval change.
- No attendance/geofence logic change.
- No Web Admin change.
- No UI/layout change.
- No employee logout caused by realtime recovery.
