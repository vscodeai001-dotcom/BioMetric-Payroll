# 1100-B Web Employee Management Firebase-only conversion

## Scope
Employee Management is the first Web module converted from EF/SQLite runtime reads and writes to the Firebase SSOT.

## Runtime path
- Employee list: Firebase `owners/{ownerUid}/employees`.
- Company settings: Firebase `owners/{ownerUid}/company_settings/1`.
- Feature settings: Firebase `owners/{ownerUid}/feature_settings/1`.
- Employee create/update: Firebase `employees/{employeeId}`.
- Employee soft delete: Firebase `employees/{employeeId}` with `isActive=false`.
- Employee Auth/profile reconciliation: Firebase Authentication + Firebase profile/provisioning status.
- Employee audit: Firebase `audit_logs`.

## Preserved behavior
- Existing Employee Management UI/layout is unchanged.
- Existing validation and salary component validation are unchanged.
- Existing soft-delete/recycle-bin workflow remains.
- Existing Auth lifecycle rules are retained: an existing Admin/SuperAdmin Firebase account cannot be stamped as an Employee; disabled employee accounts are not silently re-enabled.
- Android continues to consume the same Firebase `employees` contract.

## Temporary compatibility note
The Web application still contains legacy EF/SQLite services for modules not yet migrated. They are not used by `EmployeeList.razor` for Employee CRUD/settings/deletion checks. They remain until their respective 1100 modules are converted and verified.

## Realtime
The existing Web realtime notification path is retained as UI invalidation transport. The Employee screen reloads its data from Firebase after the notification; Firebase remains the data source.
