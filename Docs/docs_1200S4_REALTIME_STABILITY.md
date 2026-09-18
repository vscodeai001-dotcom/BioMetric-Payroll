# 1200-S4 Realtime Stability / Live Employee Wire

Baseline: 1200ContFixing_ADMIN_FREEZE_LIVE_LOCATION_needFIX(1).zip

Repairs:
- Removed AdminRealtimeCoordinator and reconnect startup from Application.onCreate.
- Realtime synchronization now starts only after Firebase authentication.
- Removed duplicate data hydration from AdminRealtimeCoordinator.
- Removed Admin coordinator startup from employee login.
- MainActivity starts Admin realtime invalidation once after authenticated navigation.
- FirebaseSyncManager startSync is auth-gated.
- Removed the second FirebaseDatabase persistence call from FirebaseSyncManager.
- Android Admin no longer invalidates/layouts the hidden command map on every GPS event.
- Delayed automatic backup work so it cannot compete with login/map startup.
- Firebase employee/attendance backup conversion tolerates String numeric IDs.
- Web LiveStaffLocationPanel no longer downloads tracking/live.json for every SSE event.
- Web live changes are applied incrementally from Firebase SSE paths.
- Active session membership is retained when GPS packets become stale; age only controls status.
- Explicit live/session ENDED/OFFLINE events remove the employee marker.
- Web GPS JSON scalar parsing now tolerates Number and String representations.

Intentionally unchanged:
- Payroll calculation formulas
- Attendance business rules
- Shift business rules
- Screen/layout structure
- Firebase owner/tenant namespace
- Existing employee provisioning rule
- No empty/unlinked Employee user creation

Validation:
- Kotlin/C#/Razor structural checks performed.
- Full Android Gradle build not claimed because required Gradle distribution is unavailable in this environment.
