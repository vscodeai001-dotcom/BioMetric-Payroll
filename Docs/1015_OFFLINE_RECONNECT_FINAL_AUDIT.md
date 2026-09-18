# 1015 Offline / Reconnect Final Audit

## Scope

This module audits and hardens the Android offline/reconnect path without changing existing screens, layouts, Room business models, payroll calculations, or the Firebase SSOT architecture.

## Locked flow

```text
ONLINE
  -> Firebase realtime listeners
  -> Room local cache

OFFLINE
  -> Firebase Realtime Database disk persistence queues Firebase writes
  -> Room remains available for existing local/offline screens
  -> GPS writes use the durable Room offline_locations queue

APP RESTART WHILE OFFLINE
  -> Firebase persisted queue remains available
  -> Room database remains available
  -> WorkManager work is durable and can resume later

NETWORK RETURNS
  -> Firebase .info/connected becomes true
  -> FirebaseReconnectCoordinator restarts/ensures realtime infrastructure
  -> OfflineSyncWorker is scheduled for GPS queue drain
  -> Firebase -> Room listeners reconcile live changes

WEB / ANDROID
  -> both observe Firebase and receive realtime committed changes
```

## Implemented hardening

### 1. Reconnect recovery coordinator

`FirebaseReconnectCoordinator` observes Firebase `.info/connected` and, after reconnect:

- ensures `FirebaseSyncManager.startSync()` is active
- ensures `FirebaseRoomHydrator.start()` is active
- schedules the durable GPS `OfflineSyncWorker`
- never requires Payroll.Web, Render, or Neon
- is application-scoped and idempotent

### 2. Durable GPS queue

Existing `offline_locations` records retain their state through process/app restart. `OfflineSyncWorker` uses:

- PENDING / IN_FLIGHT / FAILED / SYNCED states
- stale IN_FLIGHT recovery
- unique WorkManager work
- network constraints
- exponential retry
- ordered queue draining

A GPS record uses `clientEventId` and `sequence`; Firebase live-location writes reject an older/equal sequence for the same session. History uses the stable client event key. This prevents a reconnect from blindly duplicating an already accepted GPS event.

### 3. Firebase offline writes

Realtime Database persistence remains enabled. Existing Firebase writes therefore remain queued by the Firebase SDK when connectivity is unavailable. No second parallel HTTP/API queue was introduced.

### 4. Room hydration safety

Initial Room/Firebase reconciliation deletes only Room records already marked as synchronized and absent from Firebase. Unsynchronized local records are not deleted during stale-row cleanup.

### 5. Numeric declaration ID race hardening

Employee Tax/FBP numeric ID allocation now uses a Firebase transaction for the counter instead of an unsafe read-then-write sequence. The existing table scan remains the migration-safe floor.

### 6. Duplicate identity policy

Existing stable IDs are preserved:

- UUID IDs for UUID-backed employee requests
- existing numeric IDs for Tax/FBP
- stable punch IDs
- GPS `clientEventId` + sequence
- Firebase record key remains the identity

No random new key is generated on retry for an already-created request.

## Conflict policy

- Firebase is the cross-platform realtime source of truth.
- Existing Web/SQL calculation boundaries are not rewritten by this module.
- Android Room is the local/offline cache and must not silently delete pending local records during reconnect reconciliation.
- Same Firebase key means an idempotent overwrite, not creation of a second record.
- GPS sequence/session guards prevent older reconnect payloads from replacing newer points.
- Calculation-sensitive operations remain behind their existing controlled boundary until their dedicated parity module explicitly replaces them.

## Verification performed

- Kotlin source structure inspected for offline queue, WorkManager, Firebase persistence, Room hydration and duplicate IDs.
- `FirebaseReconnectCoordinator.kt` added and wired at application startup for persisted authenticated sessions.
- Tax/FBP counter allocation changed to Firebase transaction semantics.
- No Room schema migration was added.
- No existing UI layout was changed.
- No payroll calculation rule was changed.
- No Neon/Render dependency was introduced.
- ZIP integrity and static source checks are performed for this package.

## Build limitation

A full Gradle compile is not claimed here unless the environment can resolve the project's configured Gradle/dependencies. The package therefore reports source/static verification separately from a real Android build.
