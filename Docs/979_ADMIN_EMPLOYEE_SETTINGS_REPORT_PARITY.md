# 979 Admin Employee / Settings / Reports parity

- Web remains the business-rule reference.
- Firebase is the shared realtime synchronization layer.
- Android Room is the offline/cache projection.
- FeatureSettings in Android Settings is now editable through Firebase, with Web Geo-Fencing master semantics: disabling Geo-Fencing forces Dual Attendance and Automatic Geofence Punching off.
- Settings writes are restricted to SuperAdmin or Admin with `adminCanEditSettings`.
- Report Center now supports employee filtering for attendance and renders the Web-style daily-summary drill-down.
- Financial Register uses available employee identity/salary fields rather than placeholder values.
- Recycle Bin deep links are SuperAdmin-only, matching the Web authorization boundary.
- Firebase Auth account creation/role claims are not impersonated by the Android client; user provisioning remains a controlled server-side/Web boundary until a verified server action is available.
