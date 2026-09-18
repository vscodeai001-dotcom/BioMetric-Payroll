# 975 Android/Web Parity Pass

## Scope
This pass continues the Web-as-reference, Android-as-mobile-mirror architecture.

### Employee
- Employee self-service data screens now use `FirebaseEmployeeSelfServiceRepository` instead of `MobileApiService`.
- Reads: attendance, payslips, leave, advances, bonuses, regularizations, resignation, tax, FBP and shifts are Firebase-backed.
- Writes: leave, regularization, resignation, tax and FBP are Firebase-backed.
- Existing Firebase realtime invalidation remains responsible for refreshing the UI.
- Firebase Authentication remains the identity layer.

### Admin/SuperAdmin foundation
- Main dashboard company settings and feature settings are Firebase-first and no longer depend on the Mobile API in `MainViewModel`.
- Existing Room projection remains available for offline/admin dashboard calculations.
- Complex payroll/attendance calculation endpoints are intentionally not duplicated in Android in this pass. They remain behind the Web calculation boundary until the calculation parity is verified.

## Non-negotiable parity rules
1. Web business rules are the reference behavior.
2. Android must not silently invent alternate calculations.
3. Firebase is the shared realtime synchronization source.
4. Room is an offline/local projection, not a second authority.
5. SQL/Web compatibility remains protected during phased migration.
6. Feature toggles must come from the shared Firebase settings record.
7. Employee punch/geofence decisions must use the centralized state machine.
8. GPS session lifecycle must exist before GPS points are processed by the Web geofence engine.

## Remaining active MobileApiService call sites
The remaining calls are deliberately isolated and require separate parity work:
- authentication compatibility path (`AuthRepository`)
- Admin attendance calculation/report endpoint
- Admin payroll preview/history/finalization calculation endpoint
- Admin finance actions
- legacy employee/compatibility activity paths outside the Firebase self-service path
- theme compatibility synchronization

These should not be deleted blindly because some endpoints contain Web business calculations. Each must either be migrated to an equivalent Firebase data/read-model operation or retained as an explicit Web calculation boundary.
