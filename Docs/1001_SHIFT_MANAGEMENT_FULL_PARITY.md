# 1001 Shift Management / Shift Schedule Full Parity

## Architecture locked
- Firebase Realtime Database is the shared SSOT.
- Web and Android are standalone clients.
- No Neon, Npgsql, Render API, or Android -> Web dependency is used by the shift module.
- Existing ShiftSchedule model, screen structure, validation, and payroll calculations are preserved.

## Implemented
### Web
- Shift Scheduler reads `owners/{ownerUid}/shift_schedules` from Firebase.
- Add/save writes directly to Firebase.
- Delete writes directly to Firebase.
- Pattern generation reads employees/patterns from Firebase and publishes generated schedules in one Firebase multi-location update.
- Employee self-service shift schedule reads canonical Firebase employee and shift data.
- Feature and employee shift-view permissions are read from Firebase.
- Existing realtime refresh event path is notified after shift writes.

### Android
- Admin Shift Manager reads `shift_schedules` through a Firebase realtime listener.
- Admin add/delete/generate operations write directly to Firebase.
- Firebase Realtime Database persistence remains enabled, so queued writes survive temporary connectivity loss.
- Employee Shift Schedule observes the existing Firebase -> Room shift projection, giving realtime/offline updates without manual reload.
- TrackingWindowResolver now recognizes both concrete schedules and recurring patterns.
- Existing Room schema is unchanged.

### Firebase security
`owners/$uid/shift_schedules/$recordId` now has explicit rules:
- Admin/SuperAdmin: read/write.
- Employee: read only for their own employeeId.
- Schedule payload validation requires the canonical shift fields.

## Compatibility
The legacy MobileAdminShiftController and RosteringService remain in the repository for compatibility with older callers. The current Web Shift Scheduler and Android Shift Manager no longer depend on that API path for normal CRUD.

## Verification
- Firebase rules JSON: valid.
- Modified source brace/parenthesis structural scan: passed.
- Full Android Gradle compilation: not verified because the environment could not download Gradle 9.5.0 from `services.gradle.org`.
- Full Web .NET compilation: not verified because the environment does not have the `dotnet` SDK installed.

## Important next module
1002 should continue with the next requested module only after validating 1001 on:
1. Web admin create/edit/delete.
2. Android admin create/delete/generate.
3. Web -> Android realtime schedule update.
4. Android -> Web realtime schedule update.
5. Offline Android write -> reconnect -> Firebase -> Web.
6. Shift-mode tracking at a concrete shift and recurring-pattern shift.
7. Overnight shift crossing midnight.

## 1001 continuation audit / Firebase SSOT wiring correction

The 1001 package was re-audited before moving to 1002. The audit found several wiring gaps that would prevent true Web <-> Firebase <-> Android parity for employee-scoped shift data.

### Corrected
- Web Admin Shift Scheduler employee selector now reads active employees from Firebase instead of the legacy Web database.
- Web Employee My Shift Schedule now resolves the employee from the authenticated `employee_id` claim and reads that single employee record from Firebase.
- Web employee shift reads now use a Firebase `employeeId` constrained query instead of reading the entire shift collection.
- Firebase Web REST access now supports constrained owner-table queries.
- Firebase rules now explicitly allow employee-scoped shift reads only when `orderBy=employeeId` and `equalTo=auth.token.employee_id` are supplied.
- `shift_schedules` has Firebase indexes for `employeeId` and `shiftDate`.
- Employee clients can read the canonical `feature_settings` record without receiving write access.
- Android no longer keeps the entire shift collection synced for an employee account; it keeps only the employee-constrained query synced.
- Android Room hydration of shift schedules is role-aware: Admin/SuperAdmin receive the full schedule stream, while Employee receives only their own schedule stream.
- Tracking-window resolution now gives a concrete dated shift precedence over a recurring pattern for the same date, and selects the newest recurring pattern for a weekday, matching Web generation precedence.

### Preserved
- Existing UI/layout and navigation.
- Existing ShiftSchedule entity/schema.
- Existing recurring pattern representation.
- Existing overnight-shift behavior.
- Existing payroll calculations.
- Firebase as the single shared SSOT.
- Room as Android's offline/local projection only.

### Validation status
- Firebase rules JSON: valid.
- Modified Kotlin delimiter/structure scan: passed.
- Modified Razor delimiter/structure scan: passed.
- Full Android Gradle compilation: not verified because the configured Gradle 9.5.0 distribution could not be downloaded in the current environment (`services.gradle.org` DNS unavailable).
- Full Web .NET compilation: not verified because the current environment has no `dotnet` SDK installed.

### Remaining 1001 live verification matrix
1. Web Admin create schedule -> Android Admin updates without reload.
2. Android Admin create/delete -> Web Admin updates without reload.
3. Web Employee My Shift Schedule sees only its own shifts.
4. Android Employee Shift Schedule sees only its own shifts and updates live.
5. Offline Android schedule projection remains available and reconciles after reconnect.
6. Shift tracking window follows concrete schedules and recurring patterns.
7. Overnight shift remains active across midnight.
