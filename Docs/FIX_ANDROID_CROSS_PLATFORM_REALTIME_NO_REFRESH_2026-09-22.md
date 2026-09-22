# Android Cross-Platform Realtime No-Refresh Fix - 2026-09-22

## Requirement

Firebase/SSOT is the authoritative source. Web and Android must converge in realtime without manual refresh, reload, logout/login, or app restart.

## Changes

- Hardened Firebase -> Room realtime hydration with automatic listener rebinding after cancellation/auth/connection recovery.
- Fixed hydration lifecycle guard so the same owner does not accumulate duplicate listeners, while owner changes can rebind cleanly.
- Firebase reconnect recovery now forces a realtime hydration rebind when connectivity returns.
- Firebase `getDataFlow`, query flows, owner event flow, and global item flow automatically rebind after transient Firebase cancellation while the authenticated session remains active.
- Admin Payroll history now observes Firebase payroll history continuously instead of one-shot reads followed by manual reloads.
- Admin Finance advances, bonuses, and tax declarations now observe Firebase continuously instead of one-shot reads followed by manual reloads.
- Punch correction issue detection now reacts to the existing realtime attendance-punch flow instead of relying on the application-wide refresh callback.

## Preserved

- Firebase/SSOT remains primary and authoritative.
- Room remains the existing local cache/temporary queue; it is not promoted to SSOT.
- Existing database schema, attendance rules, payroll calculations, geofence rules, GPS flow, and business logic are unchanged.
- Existing CRUD/write paths remain in place.
- No logout/login is required to converge after a transient listener/auth interruption.

## Verification

Static source inspection completed. Full Gradle compilation could not be run in this environment because Gradle 9.5.0 is not locally cached and network access to services.gradle.org is unavailable.
