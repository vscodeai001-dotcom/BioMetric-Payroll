# Live Location Stability Continuation - 2026-09-22

Applied on top of `BioMetric-Payroll-GPS-Firebase-STABILITY-FIX-20260922.zip`.

## Live employee marker fix

- Fixed the Web Admin `LiveStaffLocationPanel` membership race where a valid GPS event could add a new employee to the in-memory `locations` collection, but the same event was then treated as a normal coordinate update and `mapNeedsUpdate` was cleared.
- The result could be `No live staff locations` even though Firebase had a valid ACTIVE employee GPS record.
- New employee/session membership changes now trigger exactly one Blazor render so the Leaflet marker is created.
- Normal GPS coordinate updates continue to use the browser-side Leaflet realtime bridge and do not trigger a full Blazor render, preserving the no-flicker behavior.
- Existing-session coordinate updates remain lightweight.

## Dashboard live-count timestamp compatibility

- `FirebaseAdminDashboardService.IsFreshActiveTracking` now falls back from `Timestamp` to `LastUpdatedUtc` when needed.
- This keeps an ACTIVE live record visible to the dashboard when a legacy/partial Firebase record has no capture `Timestamp` but does have a valid server update timestamp.

## Business logic preserved

- No attendance, payroll, shift, session, geofence calculation, or database schema rules were changed in this continuation patch.
- The existing OUTSIDE -> INSIDE / INSIDE -> OUTSIDE geofence transition logic from the supplied stability ZIP remains intact.
