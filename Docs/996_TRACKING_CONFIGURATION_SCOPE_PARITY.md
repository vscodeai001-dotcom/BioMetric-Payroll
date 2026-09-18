# 996 - Admin/SuperAdmin Tracking Configuration UI + Employee Assignment/Scope Parity

## Contract

The tracking configuration has three scopes. The effective precedence is:

`Employee override -> Shop override -> Global configuration`

An employee with no explicit employee override inherits a shop override when their synchronized `LocalEmployee.shopId` has a shop assignment. If neither exists, the employee inherits `tracking_configuration`.

## Firebase paths

- Global: `owners/{ownerUid}/tracking_configuration`
- Employee: `owners/{ownerUid}/tracking_scopes/employees/{employeeId}`
- Shop: `owners/{ownerUid}/tracking_scopes/shops/{shopId}`

## Assignment fields

- mode: `24/7`, `SHIFT`, or `CUSTOM`
- customStart: `HH:mm` when CUSTOM is used
- customEnd: `HH:mm` when CUSTOM is used
- intervalSeconds: 15..3600
- enabled: boolean
- updatedBy: authenticated Firebase UID
- updatedAt: epoch milliseconds

## Android UI

`TrackingScopeActivity` is available from Settings and supports Admin/SuperAdmin assignment management. SuperAdmin may edit directly. Admin may edit only when `feature_settings/1.adminCanEditSettings` is true.

The target list comes from the synchronized Room employee/shop projections. Clearing an override removes only the scoped override; it does not change the global configuration.

## Realtime behavior

`TrackingScopeRepository.startEffectiveListener()` observes the global configuration plus the current employee's shop and employee override. `TrackingService` consumes the effective result, so interval/mode/enabled changes propagate to the running employee tracking service without a restart.

## Security

- Admin/SuperAdmin can read/write scoped configuration.
- An Employee can read only their own employee override and the shop override belonging to their synchronized shop.
- Employees cannot write tracking assignments.
- Firebase rules require owner scope and validate mode, interval, and `updatedBy`.

## Compatibility

This module does not alter payroll or attendance calculations. It only supplies the effective tracking configuration to the existing Android tracking lifecycle.
