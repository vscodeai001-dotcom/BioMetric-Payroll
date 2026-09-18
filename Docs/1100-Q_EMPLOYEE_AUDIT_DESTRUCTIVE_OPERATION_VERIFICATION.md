# 1100-Q Employee Audit + Destructive Operation Verification

## Scope

Finalize the audit trail and safety boundary for Employee soft-delete, restore, and permanent wipe operations.

## Changes

- Added Firebase Employee deletion-state verification.
- Restore now synchronizes the Firebase Employee projection before reporting the operation as complete.
- Restore writes both SQL and Firebase audit records.
- Permanent wipe is blocked unless the Firebase Employee projection exists and is already soft-deleted.
- Permanent wipe records a Firebase `PERMANENT WIPE REQUESTED` audit event before SQL deletion.
- Permanent wipe records both SQL and Firebase completion audit events after SQL deletion.
- Existing SQL cascade/mutation behavior remains unchanged.
- Firebase Employee record is retained as a historical tombstone rather than silently disappearing, preserving the audit/realtime trail.

## Safety boundary

Permanent deletion remains a SuperAdmin-only SQL mutation. Firebase is used for Employee projection state, verification, and audit evidence. No payroll, attendance, leave, or other historical SQL calculation behavior is changed.

## Verification limitation

A full .NET build and live Firebase/SQL integration test were not executed in the available environment.
