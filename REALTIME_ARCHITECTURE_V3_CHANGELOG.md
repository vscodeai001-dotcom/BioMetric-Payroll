# BioMetric Payroll - Realtime v3

## What this phase changes

This phase makes the existing realtime invalidation path independent of Render for Android and removes browser navigation refreshes.

### 1. Shared Firebase owner scope
- Added `Firebase:OwnerUid` / `FIREBASE_OWNER_UID` support.
- Firebase custom tokens now carry an `owner_uid` claim.
- Android stores the owner UID returned during login and uses it for the existing `owners/{uid}` data path.
- Realtime events use `owner_events/{ownerUid}`.

### 2. Android realtime invalidation no longer depends on Render
- Android CRUD writes continue using the existing Firebase data writes.
- The existing `notifyRealtimeChanged` method now also publishes an owner-scoped Firebase event before attempting the legacy Render/SignalR notification.
- Android listens to recent Firebase owner events and runs the existing Neon/Room reconciliation path.
- SignalR remains enabled as a compatibility path.

### 3. Web realtime invalidation no longer causes browser reloads
- Firebase web listener uses the shared owner event path.
- Firebase and SignalR `ApplicationDataChanged` events are delivered to the existing page-level `AttendanceRefreshListener` instances.
- Removed the global `Navigation.Refresh(false)` behavior from the global listener.
- Existing screen-specific loaders remain responsible for their current data and layout.

### 4. Security rules
- Added owner-scoped rules for `owner_events`.
- Existing tracking and owner data rules remain in place.

## Required configuration

Set the same stable company owner UID on Payroll.Web/Render:

```text
FIREBASE_OWNER_UID=<shared company/admin Firebase UID>
```

The value must be the Firebase UID used as the shared owner scope. Do not put a service-account private key in Git.

## What is not changed

- Neon/PostgreSQL schema
- Payroll calculations
- Attendance calculation rules
- Existing UI/layout
- Existing navigation structure
- Existing API routes
- Existing SignalR hub/events
- Existing Room/offline GPS capture
- Existing Firebase entity paths

## Build validation

The environment used to prepare this archive does not have the .NET SDK and cannot download Gradle 9.5 because outbound network access is unavailable. Therefore final compilation must be performed in the project's normal development environment.
