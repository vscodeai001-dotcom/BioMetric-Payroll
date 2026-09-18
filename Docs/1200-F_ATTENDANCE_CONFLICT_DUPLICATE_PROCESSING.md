# 1200-F Attendance Conflict Resolution + Duplicate Processing Protection

## Base
Built directly from the supplied 1200-E source package.

## Scope
- Protect automatic attendance recalculation from concurrent Web circuits.
- Serialize recalculation by employee + business date instead of using one global component lock.
- Keep deterministic punch fingerprint idempotency.
- Include calculation-relevant punch state in the fingerprint: punch ID, time, type, biometric ID, device ID, approval state, latitude and longitude.
- Re-check idempotency after acquiring the employee/date lock.
- Respect `DailySummary.IsManualOverride` as a conflict rule.
- If a manual override is later cleared, the stored processing state no longer matches and the day can be recalculated.
- Mark a calculated fingerprint only after `SaveChangesAsync()` succeeds.
- Seed fingerprint state after page/application restart from the existing local compatibility projection.
- Do not seed a punch set when its DailySummary does not exist, so a missing summary is still calculated.
- Bound the in-process coordinator state and remove idle entries older than 24 hours after the configured capacity is exceeded.

## Architecture boundaries preserved
- Firebase remains the cross-platform SSOT/read-model source.
- Existing SQL/EF remains the compatibility and attendance calculation mutation boundary.
- Existing `AttendanceCalculatorService.CalculateDailyResult()` is unchanged.
- No database schema migration is introduced.
- No UI layout or attendance calculation formula is changed.
- No automatic deletion or deduplication of raw punch rows is performed.

## Files changed
1. `Web/Payroll.Web/Services/AttendanceProcessingCoordinator.cs`
   - New singleton coordinator with per employee/date locks and fingerprint state.
2. `Web/Program.cs`
   - Registers the coordinator as a singleton.
3. `Web/Payroll.Web/Components/Pages/Attendance/AttendanceLogViewer.razor`
   - Uses the coordinator for employee/date processing.
   - Re-checks fingerprints after locking.
   - Commits fingerprints only after successful EF save.
   - Seeds fingerprint state with DailySummary manual-override state.
   - Expands the deterministic fingerprint to include punch approval/location fields.

## Conflict behavior
| Situation | Result |
|---|---|
| Same employee/date, same punch fingerprint | Skip recalculation |
| Same employee/date, concurrent callbacks | One callback processes; later callback re-checks and skips |
| New punch added | Fingerprint changes; recalculation runs |
| Punch approval/type/device/location changes | Fingerprint changes; recalculation runs |
| DailySummary is manual override | Existing override is preserved; automatic calculation does not overwrite it |
| Manual override is cleared | Processing state includes the override state, so recalculation is allowed |
| EF save fails | Fingerprint is not committed, allowing a later retry |
| App/page restarts | Existing SQL compatibility data seeds the in-memory idempotency state |
| Punches exist but DailySummary is missing | No seed marker is created; normal calculation creates the summary |

## Verification performed
- `AttendanceLogViewer.razor` brace/parenthesis balance checked.
- `AttendanceProcessingCoordinator.cs` brace/parenthesis balance checked.
- `Web/Program.cs` brace/parenthesis balance checked.
- Confirmed no remaining references to the removed `ProcessedPunchFingerprints` or `_autoProcessLock` fields.
- Confirmed the new coordinator is registered in `Program.cs`.
- Full .NET build was not run because the environment does not have the required `dotnet` SDK available.
