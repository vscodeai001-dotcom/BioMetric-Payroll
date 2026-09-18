# 1100-M Employee Realtime Change Propagation / Invalidation Finalization

## Scope

Finalize the Firebase application-event invalidation path for Employee screens without changing Employee business calculations or mutation boundaries.

## Changes

- `ApplicationRealtimeRefreshListener` now accepts an optional `EntityFilter`.
- Employee List and Employee Details subscribe specifically to `Employee` events.
- Browser-side application events are coalesced over a short 80 ms window before Blazor invalidation.
- Entity-filtered listeners are skipped for unrelated application events.
- Listener disposal removes only the matching registration and clears pending application refresh work when no listeners remain.
- Existing unfiltered application listeners remain supported.
- Existing SignalR compatibility and Firebase realtime transports remain intact.

## Safety boundaries

- No payroll, attendance, leave, rostering, resignation, F&F, Identity, or provisioning calculation logic was changed.
- No SQL Employee mutation boundary was removed.
- Firebase remains the Employee read-model/realtime invalidation source.
- Application events remain invalidation signals; screens continue to reload their authoritative data through their existing repositories.

## Validation

- JavaScript syntax check: `node --check` passed.
- Structural brace/parenthesis counts matched for all modified files.
- Full .NET build was not run because the environment does not provide the required `dotnet` SDK.
