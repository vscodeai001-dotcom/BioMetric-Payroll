# 1200-H Attendance Punch Correction End-to-End Reconciliation

## Scope

Punch correction mutations remain on the existing SQL/EF attendance calculation boundary. After a successful SQL mutation and recalculation, the resulting AttendanceLog and DailySummary are projected to Firebase SSOT.

## Protected flows

- Admin/SuperAdmin pending correction approval
- Admin/SuperAdmin pending correction rejection/deletion
- Mobile admin manual punch create/full-day create
- Mobile admin punch edit/delete
- Manual Punch Correction page create/delete/edit

## Consistency rules

- Employee/date processing is serialized with AttendanceProcessingCoordinator.
- Correction requests are represented in Firebase as `status=PENDING` and `isApproved=false`.
- Approved requests become `status=APPROVED` and `isApproved=true`.
- Rejected requests are deleted from the Firebase attendance_punches projection.
- DailySummary is republished after recalculation.
- Firebase projection uses up to three short retries.
- A Firebase projection failure does not roll back a committed SQL mutation. The log records the projection failure for reconciliation.
- Existing attendance formulas and payroll locks are preserved.
