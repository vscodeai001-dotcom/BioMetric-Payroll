# 1200-G Attendance Manual Override / Reprocessing Integrity

## Scope

Strengthen the existing attendance manual-correction and mass reprocessing boundaries without changing the AttendanceCalculatorService formulas or the SQL/Firebase read/write architecture established by 1200-A through 1200-F.

## Changes

### Manual correction
- AttendanceCorrectionDrawer now uses AttendanceProcessingCoordinator for the exact employee/date key.
- A manual correction cannot race an automatic Firebase/SignalR punch recalculation for the same employee/date.
- After a successful save, the old automatic punch fingerprint is invalidated.
- The next automatic pass observes the current DailySummary manual-override state and preserves it.

### Approved leave synchronization
- Leave-driven DailySummary updates use the same employee/date processing lock.
- Existing manual overrides continue to be skipped.
- Successful leave-driven updates invalidate the previous automatic fingerprint for the affected employee/date keys.

### Mass Re-Process
- Each employee/date is serialized through AttendanceProcessingCoordinator.
- Existing `IsManualOverride` summaries are preserved and counted separately as skipped manual overrides.
- Locks are held until the day's database save succeeds, preventing an automatic pass from interleaving between calculation and persistence.
- After a successful day save, affected fingerprint state is invalidated and later reseeded from the persisted database state.
- Existing calculator and result mapping are unchanged.

## Safety boundary

`AttendanceProcessingCoordinator` remains an application-process concurrency/idempotency guard. It is not a distributed database/Firebase transaction lock.

## Verification

- Source structure checks: performed.
- Existing 1200-F coordinator retained.
- No attendance calculation formulas changed.
- Full .NET build was not executed because the environment does not have the required dotnet SDK available.
