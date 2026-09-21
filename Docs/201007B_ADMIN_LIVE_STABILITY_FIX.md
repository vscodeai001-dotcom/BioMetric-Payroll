# 201007B Admin Live Stability Fix

Targeted fixes applied to the 201007b base:

- Web Firebase tracking SSE partial/nested live updates are coalesced and reconciled against the authoritative employee node instead of being treated as missing live staff.
- Web transient empty/unparseable live snapshots no longer wipe a healthy live set during reconnect.
- Android Admin retains last-known-good live locations when durable-session validation temporarily fails.
- Android GPS/session client invalidations update the live-location StateFlow without triggering a whole-dashboard refresh.
- Redundant identical Android live snapshots are suppressed.
- Web collision offsets are deterministic by EmployeeId.
- Employee map fullscreen control is removed; protected fullscreen remains available only in the Admin/SuperAdmin TrackingMapActivity.
- Existing map/business/database/session logic is otherwise preserved.
