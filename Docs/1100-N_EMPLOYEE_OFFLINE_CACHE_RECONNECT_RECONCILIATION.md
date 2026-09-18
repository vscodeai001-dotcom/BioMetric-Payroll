# 1100-N Employee Offline Cache + Reconnect Reconciliation

## Scope

This module adds a browser-side read-model cache for the Web Employee Management screen.

Firebase remains the authoritative Employee store. The cache exists only to preserve the
last successfully loaded employee roster when a Firebase read temporarily fails.

## Implemented

- Added `wwwroot/js/employee-cache.js` using IndexedDB.
- Cached employee roster is keyed as `employee-roster-v1`.
- Cache entries carry a save timestamp and a 24-hour stale indicator.
- `EmployeeList.razor` writes a fresh Firebase roster into the browser cache after every
  successful Firebase read.
- When the Firebase read fails, `EmployeeList.razor` attempts to restore the cached roster.
- The UI explicitly identifies when the roster is being served from the offline cache.
- Browser `online` events trigger a fresh Firebase read automatically.
- Successful reconnect reads replace the cached roster completely, reconciling local stale
  state back to Firebase SSOT.
- Cache hydration is delayed until after the first interactive render so Blazor Server
  prerendering does not invoke JS interop prematurely.
- Cache disposal unregisters the browser online listener.

## Deliberate boundary

This is a read-cache/reconciliation module, not an offline employee mutation queue.
Employee create/update/delete operations still require the existing Firebase authorization
and write path. No local employee mutation is replayed after reconnect.

This avoids replaying stale or unauthorized changes and preserves the existing Employee
business logic and Firebase SSOT contract.

## Reconciliation flow

`Firebase Online -> Read Employee SSOT -> Render -> Replace IndexedDB cache`

`Firebase Read Failure -> Restore last cached roster -> Mark UI as cached`

`Browser online event -> Fresh Firebase read -> Replace cached roster -> Clear cached state`

## Protected boundaries

No changes were made to payroll calculations, attendance calculations, leave calculations,
rostering, F&F, Identity/Auth, provisioning, deletion authorization, or SQL calculation/mutation
boundaries.
