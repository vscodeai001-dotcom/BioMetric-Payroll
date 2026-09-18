# 1200-S Offline / Reconnect Stress Test

## Scope
Validated the Android offline GPS ledger, WorkManager reconnect drain, Firebase retry path, and tracking-session lifecycle ordering starting from the 1200-R security package.

## Changes
1. `OfflineSyncWorker` now treats `SESSION_STARTED` lifecycle events as a prerequisite for queued GPS replay.
2. `SESSION_ENDED` lifecycle events are deferred until the current queued GPS ledger has been drained, preventing an offline logout from closing the server session before its captured GPS points are replayed.
3. A failed lifecycle phase returns `Result.retry()` and leaves unsent events queued.
4. `LocationDao.getPendingForSync()` now drains only `PENDING` and `FAILED` rows. `SYNCED` is terminal and is no longer treated as a pending state.
5. Existing unique WorkManager scheduling, exponential backoff, stale in-flight recovery, original GPS timestamps, and client-event idempotency are preserved.

## Deterministic validation
`1200S_offline_reconnect_stress_test.py` passed **16/16** checks covering:
- Firebase authentication gate
- stale in-flight recovery
- capture-time ordering
- terminal synced-state exclusion
- reconnect-triggered sync
- validated network detection
- unique work serialization
- stable client event IDs
- session-start-before-GPS ordering
- session-end-after-GPS ordering
- multiple sessions during an offline period
- duplicate retry idempotency
- transient failure retry behavior

## Build limitation
A Gradle compile was attempted with `:app:compileDebugKotlin --no-daemon`, but the environment could not download Gradle 9.5.0 because outbound DNS/network access is unavailable (`UnknownHostException: services.gradle.org`). Therefore this package has static/deterministic validation, not a completed Gradle compilation.
