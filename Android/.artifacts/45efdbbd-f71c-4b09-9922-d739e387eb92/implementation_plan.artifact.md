# Implementation Plan - Marker Style & Session Binding

This plan ensures map markers correctly differentiate between geofence status (Red/Blue) and tracking status (Green dot), and finalizes session binding logic across Web and Android to prevent ghost markers.

## User Review Required

> [!IMPORTANT]
> The Web Marker fix allows a Red marker (Outside geofence) to have a Green dot (Live), which correctly represents an active session outside the work zone.

## Proposed Changes

### [Component: Web Dashboard]

#### [MODIFY] [LiveStaffLocationPanel.razor](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Components/UI/Attendance/LiveStaffLocationPanel.razor)
- Remove CSS: `.payroll-map-user-outside .payroll-map-user-status { background:#ef4444; }`.
- This ensures that "Outside" employees who are actively tracking show a **Green** dot instead of Red.

---

### [Component: Web Geofencing Service]

#### [MODIFY] [FirebaseRealtimeService.cs](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/FirebaseRealtimeService.cs)
- Add `BindLiveLocationAsync(int employeeId, Guid sessionId, string? ownerUid = null)` to set the `live` marker node's `SessionId` and `State = ACTIVE`.

#### [MODIFY] [GeoLocationService.cs](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/GeoLocationService.cs)
- In `StartGpsSessionAsync`, call `BindLiveLocationAsync` for the *new* session ID.
- This ensures the marker is "locked" to the new session, so late packets from a previously ended session (e.g. from another device) are ignored.

---

### [Component: Android Firebase Sync]

#### [MODIFY] [FirebaseSyncManager.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/sync/FirebaseSyncManager.kt)
- Re-verify and ensure `pushLiveLocation` and `pushTrackingSessionStarted` correctly implement the session ID guard.
- Cleanup: Remove unused `liveMarkerTerminator` variable.

## Verification Plan

### Manual Verification
1.  **Marker Color**: Verify that an employee outside the radius has a **Red** marker but a **Green** dot if active.
2.  **Second Device Login**: Login on Device A, then Device B. Verify that markers from Device A no longer update the map even if they arrive late.
3.  **Logout Parity**: Log out from Saara's account on Android. Verify the session ends instantly on the Web Admin dash and the marker goes offline.
