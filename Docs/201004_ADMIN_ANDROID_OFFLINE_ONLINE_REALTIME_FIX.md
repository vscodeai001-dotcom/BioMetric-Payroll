# 201004 Admin Android Offline -> Online Realtime Fix

## Fixed
- OfflineSyncWorker nullable sessionId compile error.
- MainActivity now handles SignalRManager.GlobalRefresh.
- Admin Dashboard immediately reconciles the owner-scoped tracking/live node when Firebase connectivity returns.
- Employee Android client_events invalidations now cause the visible Admin Dashboard to refresh without logout/login.
- Existing GPS/session, Room, Firebase SSOT, and screen business logic are preserved.

## Required behavior
Employee internet OFF -> Admin Android remains logged in and shows offline/stale state.
Employee internet ON -> Firebase reconnects -> canonical live node is re-read immediately -> liveLocations StateFlow updates -> map updates -> dashboard data refreshes.
