# 1200-A Attendance SSOT Foundation

## Scope
The first Attendance-domain module moves employee self-service attendance history reads to the Firebase SSOT projection.

## Firebase sources
- `owners/{ownerUid}/daily_summaries`: final calculated daily attendance results.
- `owners/{ownerUid}/attendance_punches`: canonical punch records.

## Preserved SQL boundary
- Feature/security settings remain SQL-backed.
- Attendance mutations, recalculation, payroll-lock validation, and the existing attendance engine remain behind the existing server/SQL boundary.
- No attendance calculation formula was rewritten.

## Compatibility
The read service accepts the current canonical Firebase names and selected legacy field aliases. Durations support millisecond fields and TimeSpan text.

## Security
Employee self-service reads are expected to use employee-scoped Firebase queries/rules. No owner-wide attendance query is introduced for the Employee screen.
