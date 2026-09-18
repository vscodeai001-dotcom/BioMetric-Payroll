# Android ↔ Web Full Application Parity Roadmap

## Contract

The Web application is the behavioral reference. Android may use a mobile-native UI, but must not invent different business rules, calculations, permissions, state transitions, or attendance semantics.

### Data architecture
- Firebase Realtime Database: shared realtime synchronization source for cross-platform records and tracking transport.
- Web SQL/EF Core: compatibility projection and existing calculation engine during phased migration.
- Android Room: offline/local projection and durable queue, not a competing business source of truth.
- Firebase Auth: identity, role/employee claims, and single-device session enforcement.

## Employee parity

Employee self-service must cover the Web Employee screens:
- Home/dashboard
- Attendance viewer
- Payslips
- Leave request/history
- Salary advances
- Bonuses
- Regularization
- Resignation
- Tax declaration
- Flexible benefits declaration
- Shift schedule
- Reports
- Profile

All create/update operations must persist locally when appropriate, publish to Firebase, and react to Firebase changes.

## Attendance contract

### Manual punch eligibility
Manual punch is permitted only when all of the following are true:
1. A valid current location exists.
2. The employee is within the configured office radius.
3. No punch operation is already running.
4. Location refresh is not running.
5. Biometric attendance is not active.
6. Automatic geofence punching is not active.

### Feature hierarchy
- Geo-Fencing is the master GPS switch.
- Biometric attendance is active when Geo-Fencing is disabled OR Dual Attendance is enabled.
- Automatic geofence attendance is active only when Geo-Fencing AND Automatic Geofence Punching are enabled.
- Manual punch is visible/eligible only when neither higher-priority source is active.

### Automatic geofence reconciliation
For every valid GPS fix while automatic geofence punching is enabled:
- INSIDE + attendance currently OUT → automatic IN.
- OUTSIDE + attendance currently IN → automatic OUT.
- INSIDE + attendance currently IN → no duplicate.
- OUTSIDE + attendance currently OUT → no duplicate.

Attendance parity, not only the previous GPS state, is used to self-heal after reconnects, app suspension, missed transitions, or a session starting outside the office.

### GPS session lifecycle
Android must publish the session before publishing GPS history/live events:
`tracking/sessions/{employeeId}/{sessionId}`

On explicit tracking termination it must close the exact session. Old sessions must never be allowed to overwrite a newer active session.

## Admin/SuperAdmin parity

Every Web Admin/SuperAdmin capability must have a native Android equivalent or a controlled Web calculation boundary. The migration must not weaken existing rules merely to remove HTTP calls.

Modules include:
- Dashboard
- Employee management and employee detail
- Employee permissions
- Attendance and attendance reports
- Manual punch correction
- Punch correction approvals
- Payroll preview/history/finalization
- Salary advances
- Bonuses
- Leave management
- Regularization approval
- Exit/resignation/F&F
- Shift scheduling
- Holiday/closed-day management
- Company settings
- Feature toggles
- Tax/statutory configuration and declarations
- FBP components/approvals
- Reports
- Audit logs
- Offline tracking and route replay
- Recycle bin
- User management
- SuperAdmin management

Complex payroll/attendance calculations remain on the established Web/SQL engine until equivalent Android calculations are verified against the same inputs and outputs.

## UI contract

Android UI should be premium/mobile-native with:
- clear role-aware navigation
- persistent per-user theme preference
- accessible status indicators
- compact cards and dashboards
- icons/emojis where they improve recognition
- loading/empty/error/offline states
- confirmation for destructive actions
- no visual change that alters business behavior

## Verification gate

A module is not considered parity-complete until:
1. Web behavior is identified.
2. Android behavior is mapped.
3. Firebase paths and field names are verified.
4. Offline behavior is verified.
5. permissions/features are verified.
6. create/update/delete/realtime behavior is verified.
7. calculations are compared using identical test inputs.
8. Android compile/build succeeds in an environment with the required Gradle distribution.
