# 977 Admin Attendance Parity

- Admin attendance dashboard now reads the Firebase-hydrated Room projection instead of the legacy Mobile API.
- Daily rows use persisted `daily_summaries`, employees and `attendance_punches`, matching the Web controller's displayed fields rather than recalculating payroll in Android.
- Company totals aggregate the same persisted daily-summary fields used by the Web dashboard.
- Manual punch correction now checks `payroll_history` before add/edit/delete operations, mirroring Web `PayrollLockService`: any employee payroll-history row for the punch month blocks mutation.
- Complex Web attendance recalculation remains on the Web/SQL calculation boundary. Android does not invent a second payroll engine.
