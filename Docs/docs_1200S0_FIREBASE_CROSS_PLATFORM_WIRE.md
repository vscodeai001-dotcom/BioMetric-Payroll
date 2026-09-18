# 1200-S0 Firebase Cross-Platform Wire / SSOT Foundation

## Scope

This phase hardens the shared identity and realtime data wire between Payroll.Web and the native Android application without changing screen layouts or payroll calculation rules.

## Implemented

### 1. Owner / company isolation
- Web Firebase owner resolution now prefers the authenticated `owner_uid` claim on the current request.
- Web Employee Management and Firebase runtime service boundaries use the central resolver instead of directly hard-coding the configured owner for request-time reads/writes.
- Android no longer falls back to `biometricpayroll` when a session has no `owner_uid`. The Firebase Auth/session claim must provide the tenant.
- Employee provisioning verification checks owner identity against the Firebase employee row and user profile when those fields exist.

### 2. EmployeeID / Auth / Firebase identity binding
- Web login cookie now carries `owner_uid` and `employee_id` alongside the existing device claim.
- Firebase employee records carry `ownerUid`.
- Android employee provisioning verification validates EmployeeID, email, owner and Firebase user profile identity.
- Invalid or cross-tenant employee bindings are rejected.

### 3. Employee master -> Android cache parity
- Android `LocalEmployee` now stores the complete Firebase Employee object as `payloadJson`.
- Room schema moved from version 10 to 11 with a non-destructive `payloadJson` migration.
- Android domain reconstruction reads the complete Firebase Employee payload, retaining salary, shift, leave, statutory and banking fields instead of reducing the employee to name/role/status.

### 4. Web Employee GPS -> Firebase -> Web/Android Admin
- Authoritative Web GPS updates are published to `owners/{ownerUid}/tracking/live/{employeeId}` after the committed GPS/session state.
- Live payload contains EmployeeId, OwnerUid, session, coordinates, accuracy, speed, distance, radius, geofence state and source.
- Android Admin tracking is owner-scoped and employee-scoped.

### 5. Login presence across Web + Android
- Web employee login creates an owner-scoped presence session.
- Android employee Firebase session creation creates an owner-scoped presence session.
- Web logout and Android session release remove their own presence session.
- Android Admin live-location filtering requires both a valid owner employee binding and an active employee presence session. This prevents stale tracking records for employees who are no longer logged in from appearing as live.

### 6. Leave / payroll self-service reads
- Web My Leave History reads Firebase leave records and preserves Pending / Approved / Rejected status.
- Web My Payslips reads Firebase payroll history.
- Web My Reports reads Firebase Daily Summaries, Payroll History, Leave and Advances.
- Web employee leave submission still commits through the existing SQL attendance/calculation boundary, then immediately projects the committed request to Firebase with canonical `Pending` status.

## Canonical cross-platform wire

```text
Firebase Auth UID
      |
      +--> owner_uid claim
      |
      +--> employee_id claim (Employee accounts)
      |
      v
owners/{ownerUid}/employees/{employeeId}
      |
      +--> employee master / salary / shift / leave eligibility
      |
      +--> attendance / attendance_punches
      +--> daily_summaries
      +--> shift_schedules
      +--> leave_requests
      +--> advance_payments
      +--> payroll_history
      +--> bonus_records / tax / FBP
      |
      +--> presence/{session}
      |
      +--> tracking/live/{employeeId}
      |
      +--> Android Room cache
```

## Important remaining migration boundary

Payroll calculation still has SQL/EF dependencies by design in the current project. This phase does **not** silently replace those calculations. The next audit must migrate/verify calculation inputs and outputs module-by-module before Neon/PostgreSQL can be removed.

Remaining employee self-service pages with direct SQL dependencies were identified for follow-up: MyLeaveRequest (mutation compatibility boundary), MyAttendanceViewer (feature gate only), MyFBPDeclaration, MyRegularization, MyResignation and MyTaxDeclaration. These require focused parity passes rather than broad destructive rewrites.

## Validation

- Changed Kotlin/C# source files passed structural brace/parenthesis checks.
- `Android/database.rules.json` passes JSON parsing.
- Full Android Gradle build and full .NET build were not available in the current execution environment, so this package does not claim a full compilation.
