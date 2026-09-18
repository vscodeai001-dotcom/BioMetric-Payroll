# 1009 AUDIT VIEWER FULL PARITY

## Scope
- Firebase audit_logs is the cross-platform audit SSOT for new synchronized events.
- Web Audit Viewer reads Firebase first and falls back to existing SQL audit history when Firebase is unavailable or empty, preserving historical Web-only records.
- Existing SuperAdmin-only access is preserved.
- Web filters: date range, actor, role, module/action. Old/new values and target are displayed from Firebase audit payloads.
- Android Audit Viewer already hydrates Firebase audit_logs into Room and now wires its search field into the Firebase paging source across actor, role, module, action, target, old value and new value.
- Existing audit write paths remain intact.
- Secrets are redacted by the Android AuditLogger.

## Data flow
Firebase audit_logs -> Web Audit Viewer / Android Firebase listener -> Room -> Android Audit Viewer

## Preservation
No payroll, attendance, leave, shift, finance, database schema, or calculation rules were changed. MobileApiService was not removed.

## Validation
ZIP integrity and source-level structural checks performed. Full Gradle/.NET build requires the project toolchains and network-accessible Gradle distribution.
