# 201003 Session Recovery + No-Flicker Fix

## Problem fixed
A live GPS stream could continue updating Firebase while the Web GPS session row was already ENDED. This produced:
- Location History `Last Update` increasing
- GPS point count increasing
- Session Timeline remaining `Ended`
- no new session being created
- visible dashboard refresh/flicker from a 5-second full component refresh

## Session rule
An ENDED session is never reopened.

When a current GPS fix arrives for an ended/missing session:
1. Use an already-active session for that employee if one exists.
2. Otherwise create a new GPS session with a new SessionId.
3. Keep the old session permanently ENDED.
4. Publish the new session as ACTIVE before publishing its live GPS point.
5. Android rotates to a new SessionId when Firebase reports ENDED/MISSING or a newer live SessionId.

Offline replay remains historical evidence and does not resurrect an ended session.

## UI
The Web live-location component no longer performs a full `RefreshLocations()` every 5 seconds.
Firebase realtime events update the map immediately. A 20-second reconciliation is retained only as a silent-stream safety net. Selected employee history is refreshed on session start/end.

## Validation
Modified C# and Kotlin source files have balanced braces/parentheses. Kotlin standalone parsing still reports only expected missing Android/project dependencies in this environment, with no parser-level `expecting` errors.
