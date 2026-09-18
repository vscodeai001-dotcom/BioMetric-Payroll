# 1200-S1 Live Employee Cross-Platform Fix

## Problem observed
- Android Admin dashboard showed the correct workforce/present count but the actual logged employee marker could be missing.
- Web Admin dashboard could show zero workforce/live count or unrelated stale employees.
- The Web live map was reading SQL EmployeeGpsSessions while Android was reading owner-scoped Firebase tracking/live.

## Fixes
1. Web LiveStaffLocationPanel now reads owner-scoped Firebase `owners/{ownerUid}/tracking/live` as the live marker source.
2. Web subscribes to the owner-scoped Firebase tracking stream and refreshes the live snapshot when the shared wire changes.
3. Web live marker membership requires both a Firebase live record and an employee belonging to the current owner.
4. Ended/offline/older-than-120-second Firebase live markers are not rendered.
5. Web GeoLocationService publishes committed browser GPS fixes to the same Firebase owner-scoped live branch with `State=ACTIVE`.
6. Android MainActivity re-renders live markers whenever the owner employee cache hydrates, removing the startup race where a live GPS packet arrived before the employee master.
7. Firebase Admin Dashboard aggregation now accepts both object-shaped and array-shaped Realtime Database collections and reads common PascalCase/camelCase field variants.

## Preserved
- Existing attendance/geofence calculation engine.
- Existing SQL compatibility store.
- Existing UI/layout.
- Existing employee business rules.
- Existing Android offline Room architecture.

## Build note
A full .NET build could not be executed in this environment because the `dotnet` executable is unavailable. Static brace/parenthesis checks pass for all changed source files.
