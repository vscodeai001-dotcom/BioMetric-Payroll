# 997 - Tracking Scope Realtime Reconciliation

## Purpose
Ensure an employee's effective tracking scope changes immediately when an Admin/Web change updates the employee's shop assignment or when a Firebase tracking scope is added, changed, or removed.

## Effective precedence
1. Employee override
2. Current employee shop override
3. Global tracking configuration

## Runtime behavior
- `TrackingScopeRepository` observes the owner-scoped `tracking_scopes` subtree and global tracking configuration.
- The running `TrackingService` also observes the Room employee projection for the authenticated employee.
- When `shopId` changes, the previous shop scope is no longer used; the effective configuration is recomputed from the new employee binding.
- Repeated service start/recovery commands first remove old Firebase listeners to avoid duplicate configuration callbacks.
- Employee scope deletion causes fallback to shop/global configuration.
- Shop scope deletion causes fallback to global configuration.
- Global changes remain visible even when an override exists, so removing an override immediately exposes the current inherited value.

## Safety
- No payroll or attendance calculation is performed here.
- Firebase remains the shared realtime transport.
- Room remains the local employee projection.
- Authenticated employee identity remains the source for the employee scope key.
