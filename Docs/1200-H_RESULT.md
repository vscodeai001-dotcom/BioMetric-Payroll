# 1200-H Result

## Attendance Punch Correction End-to-End Reconciliation

Built from the 1200-G base.

### Implemented

- Firebase-first pending punch correction reads with SQL compatibility fallback.
- Admin/SuperAdmin correction approval protected by the existing payroll lock and the employee/date AttendanceProcessingCoordinator lock.
- Approval commits the existing SQL mutation, recalculates the DailySummary, then projects the approved punch and final DailySummary to Firebase.
- Rejection deletes the SQL correction punch and then deletes the Firebase attendance_punches projection.
- Mobile Admin manual create, full-day create, edit and delete now use the same employee/date processing lock and Firebase projection boundary.
- Manual Punch Correction page create, full-day create, edit and delete now project resulting punch state and final DailySummary to Firebase.
- Correction requests are represented as `status=PENDING` and `isApproved=false` in Firebase.
- Approved corrections are represented as `status=APPROVED` and `isApproved=true`.
- Firebase attendance reads now honor the explicit `isApproved` flag and correctly treat PENDING/REJECTED states as not approved when no flag exists.
- DailySummary is published after recalculation.
- Firebase projection uses three short transient retries.
- Application-event publication is best effort after the actual Firebase SSOT write, so a notification-event failure does not falsely report the SSOT write as failed.
- Existing attendance calculation formulas, payroll locks, UI layout, and database schema remain unchanged.
- India/Kolkata business-date conversion is used when projecting punch timestamps to Firebase epoch milliseconds.
- `AttendanceProcessingCoordinator` is explicitly registered as a singleton in Web DI.

### Verification

- Source brace balance: PASS for all modified C# / Razor files.
- Parenthesis balance: PASS for all modified C# / Razor files.
- DI registration presence: PASS.
- Firebase attendance table usage: PASS.
- ZIP integrity: PASS.
- Full .NET build: NOT RUN because the execution environment does not have the required `dotnet` SDK installed.
