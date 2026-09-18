# 1007 Admin Dashboard Full Parity

## Scope
- KPI cards
- Attendance summary
- Payroll summary
- Employee status
- Live tracking card/panel
- Realtime refresh
- Role-specific visibility

## Firebase SSOT boundary
The Admin Dashboard no longer uses the legacy Web database as its dashboard read authority. It aggregates already-published Firebase SSOT records from:

- `owners/{ownerUid}/employees`
- `owners/{ownerUid}/attendance`
- `owners/{ownerUid}/advance_payments`
- `owners/{ownerUid}/payroll_history`
- `owners/{ownerUid}/shift_schedules`
- `owners/{ownerUid}/daily_summaries`
- `tracking/live`

No second attendance or payroll calculation engine is introduced.

## KPI compatibility
The existing Web dashboard semantics are retained:

- Total Workforce = Firebase employee records.
- Active Employees = active Firebase employee records.
- Present Today = distinct active employee IDs with today's Firebase attendance punch.
- Absent Today = active employees minus present employees, clamped at zero.
- Unpaid Advances = unrecovered Firebase advances.
- Payroll status/cost = existing published payroll-history periods.
- Shifts Today = Firebase shift schedules for today's date.
- Monthly scheduled hours = Firebase daily-summary scheduled duration.

## Realtime
The dashboard component opens Firebase Realtime Database streams for the authenticated owner's SSOT node and the global tracking tree. Relevant changes trigger the existing dashboard state refresh without browser reload.

The legacy attendance refresh listener remains in place for compatibility with existing screens. It is not the dashboard's only realtime mechanism.

## Role visibility
- Employee: redirected to Employee Home.
- Admin: dashboard and live tracking panel when Geo-Fencing is enabled.
- SuperAdmin: dashboard and live tracking panel; existing SuperAdmin Geo-Fencing override remains intact.

## Compatibility
- Existing Home.razor layout and navigation targets are preserved.
- Existing payroll calculations are untouched.
- Existing attendance calculations are untouched.
- Existing Web/SQL calculation boundaries remain for modules that require them.
- No Neon dependency introduced.
- No Room schema migration introduced by this module.
