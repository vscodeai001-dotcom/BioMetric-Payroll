# 984 - Employee Lifecycle Synchronization

## Purpose

Keep an already-authenticated Android Employee session aligned with Web/Admin lifecycle changes without rewriting payroll, attendance, leave, shift or calculation logic.

## Live contract

```text
Web/Admin Employee change
        |
        +--> owners/{owner_uid}/employees/{employee_id}
        |       isActive / employeeId / email / profile
        |
        +--> employee_sessions/{firebase_uid}
        |       deviceId / employeeId / ownerUid
        |
        +--> Firebase Auth custom claims
                role / owner_uid / employee_id

Android
  -> employee lifecycle listener
  -> single-device session listener
  -> periodic ID-token claim refresh
  -> provisioning verification
  -> invalidate stale session
  -> LoginActivity
```

## Rules

- Firebase remains the cross-platform realtime identity/session source.
- Web/SQL remains the existing compatibility/business-calculation boundary.
- Android never changes Firebase Auth custom claims from the client.
- Employee deactivation/termination invalidates the Android session.
- Employee record removal invalidates the Android session.
- Another device replacing the session invalidates the previous device.
- Owner/employee claim changes are detected after token refresh.
- Existing Employee screens and payroll/attendance calculations are not rewritten.
