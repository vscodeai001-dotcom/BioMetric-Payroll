# 1200-K Midnight / Cross-Day Shift Handling

## Scope

Overnight shifts are assigned to the original `ShiftDate` while their attendance interval may continue into the following calendar day.

Example:

- ShiftDate: `2026-09-17`
- Shift: `22:00 -> 06:00`
- Effective interval: `2026-09-17 22:00` through `2026-09-18 06:00`

No database schema changes are introduced.

## Implemented

- `AttendanceBoundsService` now treats `EndTime <= StartTime` as an overnight shift and adds one day to the scheduled end.
- `WorkDayCutoffHour` no longer truncates an explicitly scheduled overnight shift.
- `AttendanceCalculatorService` resolves the effective shift before punch processing.
- For overnight shifts, the calculator loads next-calendar-day punches needed to complete the previous ShiftDate.
- Punches through the scheduled overnight end are included in the previous ShiftDate.
- When the overnight sequence is still open at the scheduled end, the first post-end OUT is captured so legitimate post-shift OT is not lost.
- A later independent next-day IN is not pulled into the previous ShiftDate.
- Current open punches on overnight shifts can remain live across midnight until the scheduled overnight end.
- Mobile Admin punch recalculation also reprocesses the previous ShiftDate when that date has an effective overnight shift.
- Regularization recalculation applies the same previous-overnight protection.
- Existing `AttendanceCalculatorService` remains the calculation authority.
- Existing `DailySummary`, payroll-lock, manual-override, Firebase SSOT, and SQL compatibility patterns remain intact.

## Attendance ownership rule

For an overnight shift, the original ShiftDate owns the shift's cross-midnight attendance interval. The following calendar day is not automatically merged into that previous shift after the overnight sequence has closed.

## Examples

### 1. Normal overnight completion

`22:00 IN -> 02:00 OUT -> 02:30 IN -> 06:00 OUT`

All four punches belong to the ShiftDate on which the 22:00 shift started.

### 2. Post-shift overtime

`22:00 IN -> 06:30 OUT`

The 06:30 OUT is retained as the closing OUT for the overnight shift, so the 30 minutes after scheduled end can be calculated as post-shift overtime.

### 3. Next day's independent session

`22:00 IN -> 06:00 OUT -> 09:00 IN -> 18:00 OUT`

The 09:00/18:00 session is not absorbed into the previous overnight ShiftDate.

### 4. Open overnight punch

`22:00 IN`, current India time `02:30` the next day.

The previous ShiftDate remains `Present` with the open punch measured to current India time. No fake OUT is written.

## Validation

- Modified C#/Razor brace balance: PASS
- Modified C# parenthesis balance: PASS
- .NET full build: NOT RUN, .NET SDK unavailable in the execution environment
- Database schema migration: NOT REQUIRED
