v16 - Firebase SSOT foundation

Modified files:
- Web/Payroll.Web/Services/FirebaseRealtimeService.cs
- Web/Payroll.Web/Controllers/FirebaseSsotController.cs
- Web/Payroll.Web/wwwroot/js/attendance-refresh.js
- Android/app/src/main/java/com/biometric/app/sync/FirebaseSyncManager.kt

Purpose:
- Adds table-whitelisted Firebase owner CRUD primitives for the Web migration.
- Adds authenticated Admin/SuperAdmin Firebase SSOT CRUD gateway.
- Makes Web realtime event transport Firebase-first instead of starting SignalR as the realtime transport.
- Removes Android's legacy SignalR notification from FirebaseSyncManager's realtime change path.

Important:
This is a staged cutover foundation. Existing payroll/business EF services are not silently rewritten.
Do not delete Neon until every business module has been migrated and verified against Firebase.
