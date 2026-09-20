# 201003 Fix: Session Integrity + Offline GPS Attendance + Playback Compile Errors

## Fixed
- Restored all missing LiveStaffLocationPanel playback event handlers and polling methods.
- Escaped Razor CSS at-rules (`@@media`) so CSS is not parsed as C#.
- Android tracking no longer trusts a stale persisted server-session flag after another
  platform ends the Firebase session. It verifies the remote session and creates a new
  SessionId when the old session is ENDED.
- Web session start now publishes the durable Firebase tracking session alongside the
  live marker.
- Web session end now publishes the durable Firebase session as ENDED.
- Cross-platform live termination checks the expected SessionId before ending a marker.
- OfflineSync stale SESSION_STARTED events for already-ended sessions no longer block
  the durable GPS queue.
- Offline GPS history (`CaptureSource=OfflineSync`) is used as recovery evidence for
  the existing server automatic geofence attendance engine. It is restricted to the
  original GPS session window and never resurrects the GPS session.
- Historical automatic punch audit time follows the captured GPS event time.
- Route playback controls now compile and poll JS playback state without fullscreen.

## Attendance boundary
The existing `EnableAutomaticGeofencePunching` feature flag remains authoritative.
If automatic IN/OUT is disabled in Feature Settings, the application intentionally does
not create automatic geofence punches.
