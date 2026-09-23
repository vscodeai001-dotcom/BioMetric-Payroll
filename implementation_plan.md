# Implementation Plan — Firebase Spark vs Blaze Plan Modes & Bandwidth Optimizations

Configure a SuperAdmin plan mode setting (**Spark** vs **Blaze**) that automatically propagates across Web, Android Admin, and Employee apps. When **Spark** mode is selected, apply Option B optimizations to reduce Firebase RTDB download bandwidth by 80% without modifying existing layout, screen structure, or core business calculation logic.

---

## Technical Approach & Strategy

### 1. Plan Mode Configuration (SuperAdmin SSOT)
- Add `FirebasePlanMode` (`"Spark"` | `"Blaze"`, default `"Spark"`) property to `FeatureSettings`.
- Expose a clean plan selector (Spark / Blaze) in SuperAdmin Feature Settings on the Web dashboard.
- Sync changes via Firebase RTDB `owners/{ownerUid}/feature_settings/firebase_plan_mode` so all Android and Web clients pick up the selected mode in real time.

---

### 2. Spark Mode Bandwidth Optimizations (Option B)

#### B1. Restrict `keepSynced(true)` in Android (`SignalRManager.kt`)
- **Old:** `liveRef.keepSynced(true)` kept the **entire** `tracking/live` tree synced offline across all Admin & Employee devices.
- **Spark Mode:** Restrict `keepSynced(true)` **only** to the individual employee's own location node (`owners/{ownerUid}/tracking/live/{myEmployeeId}`) when logged in as an Employee. For Admins or in Spark mode, do not keep the full multi-employee tree pinned in memory.
- **Blaze Mode:** Standard full tree sync.

#### B2. Web TTL Cleanup for Tracking History (`FirebaseRealtimeService.cs`)
- When publishing GPS location updates in `PublishLiveLocationAsync()`, if in **Spark** mode, asynchronously purge `tracking/history/{employeeId}` entries older than 24 hours.
- Prevents history nodes from growing endlessly and clogging downloads.

#### B3. Query Limits for Tracking History (`SignalRManager.kt` & Web Admin)
- When reading tracking history for map playback or tracking popups in **Spark** mode, apply `.limitToLast(50)` on Firebase queries instead of downloading all historical points.

#### B4. Dynamic GPS Update Frequency (`GeoLocationService.cs` & Android `GpsTrackingService.kt`)
- **Spark Mode:** Send GPS updates every 45–60 seconds (sufficient for attendance/payroll).
- **Blaze Mode:** Send GPS updates every 15 seconds.

#### B5. Smart Standby Reconciliation (`SignalRManager.kt`)
- In `SignalRManager.kt`'s periodic watchdog, check `lastSuccessfulLiveReadAt`.
- **Spark Mode:** Only trigger `reconcileLiveLocationsNow()` if `now - lastSuccessfulLiveReadAt > 60,000 ms` (1 minute).
- Avoids redundant 15s REST polling when live Firebase listeners are healthy.

---

### 3. Startup Timeout Fix (`FirebaseSuperAdminProvisioningService.cs`)
- Increase `CancellationTokenSource` timeout from `15 seconds` to `45 seconds` in `EnsureFirebaseAuthAsync()` to eliminate startup `TaskCanceledException` during OAuth token fetch.

---

## Proposed Changes

### [Component: Web — Core & Settings]

#### [MODIFY] [FeatureSettings.cs](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Shared/Services/Admin/FeatureSettings.cs)
- Add `[Column("firebase_plan_mode")] public string FirebasePlanMode { get; set; } = "Spark";`

#### [MODIFY] [FirebaseSuperAdminProvisioningService.cs](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/FirebaseSuperAdminProvisioningService.cs)
- Increase `CancelAfter` from `15s` to `45s` in lines 86 & 92.

#### [MODIFY] [FirebaseRealtimeService.cs](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/FirebaseRealtimeService.cs)
- In `PublishLiveLocationAsync()`, check `FeatureSettings.FirebasePlanMode`. If `"Spark"`, invoke 24-hour TTL cleanup on `tracking/history/{employeeId}`.

#### [MODIFY] [GeoLocationService.cs](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/GeoLocationService.cs)
- Adapt publish interval based on `FeatureSettings.FirebasePlanMode` (45s for Spark, 15s for Blaze).

---

### [Component: Android — Sync & Service]

#### [MODIFY] [SignalRManager.kt](file:///e:/Project/Android%20App%20Projects/BioMetric+Payroll/BioMetric+Payroll/android/app/src/main/java/com/biometric/app/sync/SignalRManager.kt)
- Update `start()` to restrict `keepSynced(true)` to employee's own node in Spark mode.
- In `reconcileLiveLocationsNow()` and `realtimeHealthJob`, apply 60s cooldown check in Spark mode.

---

## Verification Plan

### Automated Tests
- Run `dotnet build` in `Web/Payroll.Web` to ensure clean compilation.
- Run `./gradlew assembleDebug` in `android` to ensure clean Android build.

### Manual Verification
1. Log in as SuperAdmin in Web dashboard → Navigate to Feature Settings.
2. Toggle between **Spark** and **Blaze** mode → Verify setting saves and syncs to RTDB `feature_settings`.
3. Check Android Admin app → Verify map updates correctly under Spark mode without high download consumption.
4. Verify SuperAdmin provisioning service starts cleanly without `TaskCanceledException`.
