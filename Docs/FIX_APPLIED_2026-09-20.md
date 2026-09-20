# Firebase LeaveRequest Sync Fix

## Error addressed
The Web host was stopping during Firebase -> SQLite compatibility synchronization with:

`LeaveRequest cannot be tracked because another instance with the same key value for {LeaveRequestID} is already being tracked.`

The stack trace points to `FirebaseSqliteSyncService.UpsertTableAsync` / `UpsertRecordAsync` during `SaveChangesAsync`.

## Changes
1. `LeaveRequest` Firebase records are now projected one record per short-lived EF `DbContext`, preventing multiple leave entities from sharing an identity map during the same batch.
2. Firebase-provided primary keys are treated as authoritative for new compatibility rows. Generated-key properties are explicitly marked non-temporary before the entity is changed to `Added`, preventing SQLite/EF store-generated key propagation from replacing the Firebase key.
3. A bad/duplicate leave row is logged and skipped instead of terminating the entire Web host. Remaining leave records continue to synchronize.
4. No database schema, payroll calculation, leave business rule, Firebase schema, or UI layout was changed.

## Verification
The uploaded diagnostic log was analyzed. The reported exception is reproduced in the supplied trace at the `FirebaseSqliteSyncService.UpsertTableAsync` call path.

The available execution environment for this repair does not contain the .NET SDK, so a local `dotnet build` could not be executed here. Please run the normal solution build on the development machine before deployment.
