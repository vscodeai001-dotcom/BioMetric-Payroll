# Realtime v17 - Standalone Firebase Tracking

This patch makes the Android employee GPS/realtime tracking path independent of
Payroll.Web, Render, Neon, and SignalR.

Modified files:
- Android/app/build.gradle.kts
- Android/app/src/main/java/com/biometric/app/sync/FirebaseSyncManager.kt
- Android/app/src/main/java/com/biometric/app/sync/SignalRManager.kt
- Android/app/src/main/java/com/biometric/app/domain/location/TrackingService.kt
- Android/app/src/main/java/com/biometric/app/domain/location/OfflineSyncWorker.kt
- Android/app/src/main/java/com/biometric/app/ui/MainActivity.kt
- Android/app/src/main/java/com/biometric/app/sync/DashboardWarmingWorker.kt

Behavior:
- TrackingService writes GPS directly to Firebase.
- OfflineSyncWorker drains the local Room GPS queue directly to Firebase.
- SignalRManager remains as a compatibility API for existing screens, but it
  no longer opens a SignalR/Payroll.Web connection; it listens to Firebase.
- Admin live map uses Firebase live locations instead of polling Payroll.Web.
- DashboardWarmingWorker no longer calls Payroll.Web.
- SignalR/Reactively-related Gradle runtime dependencies are removed because
  the Android implementation no longer uses those libraries.

This does not modify XML layouts, Room schema, payroll calculations, or
existing business rules.

Important:
- Firebase Authentication must already be established for the employee
  session. The existing login/custom-token bootstrap is not changed in this
  patch.
- This patch makes the 24/7 tracking/realtime path independent. Other legacy
  employee API operations (login, punch, dashboard, payslips, etc.) are still
  being migrated in subsequent module-specific batches.
