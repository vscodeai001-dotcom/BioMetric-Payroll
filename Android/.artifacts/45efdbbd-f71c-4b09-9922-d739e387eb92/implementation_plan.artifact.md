# Implementation Plan - Cross-Platform Sync & Logout Stability (v2)

This plan ensures that GPS sessions are accurately terminated across all platforms upon manual logout, and that live map markers reflect the current active session without "ghosting". It also fixes the Web logout 404 error.

## User Review Required

> [!IMPORTANT]
> The Web Logout fix will restore the correct Identity path (`/Identity/Account/Logout`).
> The Android Logout fix ensures the background service stays alive until Firebase is notified of the session termination.

## Proposed Changes

### [Component: Web UI]

#### [MODIFY] [LoginDisplay.razor](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Components/Layout/LoginDisplay.razor)
- Correct the logout redirection path to `/Identity/Account/Logout`.
- Use a leading slash for the form action to ensure it resolves from the root.

---

### [Component: Web Geofencing Service]

#### [MODIFY] [FirebaseRealtimeService.cs](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/FirebaseRealtimeService.cs)
- Add `TerminateLiveLocationAsync(int employeeId, string ownerUid)` to explicitly set the `live` marker to `State = ENDED`.

#### [MODIFY] [GeoLocationService.cs](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/GeoLocationService.cs)
- In `EndAllGpsSessionsAsync`, call `TerminateLiveLocationAsync` to sync the termination to Firebase.
- In `StartGpsSessionAsync`, when ending previous sessions, also call `TerminateLiveLocationAsync` for each old session to ensure the map marker is durably un-bound from them.

---

### [Component: Android Tracking Service]

#### [MODIFY] [TrackingService.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/domain/location/TrackingService.kt)
- Update `stopTracking` to be a `suspend` function (or use a coroutine) that ensures `pushTrackingSessionEnded` is fully committed in Firebase before the service terminates.
- This prevents the race condition where the service kills itself before notifying the server of the logout.

---

### [Component: Firebase Synchronization]

#### [MODIFY] [FirebaseSyncManager.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/sync/FirebaseSyncManager.kt)
- **Authoritative Session Binding**:
    - Update `pushTrackingSessionStarted` to atomically update the `live` marker node with the new `SessionId` and set `State = ACTIVE`.
    - Update `pushLiveLocation` to use a Firebase transaction that compares the incoming `SessionId` with the one stored in the `live` marker.
    - If the IDs don't match (meaning a newer session has started), the late location point is rejected for the `live` marker (but still saved to `history`).
- **Durable Logout**: Fix the unused variable warning by correctly applying the `State = ENDED` update to the live marker node.

## Verification Plan

### Automated Tests
- Build both Android and Web projects.

### Manual Verification
1.  **Web Logout**: Click logout on the Web Portal. Verify it no longer 404s and the user is redirected to the login page.
2.  **Android Logout**: Log out from Saara's account on Android. Verify the Admin dashboard immediately shows the session as "Ended" and the marker goes "Offline".
3.  **Cross-Device Conflict**: Log in on Device A, then Device B. Verify Device A's marker is correctly terminated in Firebase.
