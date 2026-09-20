# Implementation Plan - Logout Punches & Map Selection Fixes

This plan ensures that employees are automatically punched "OUT" upon manual logout if their attendance state is "IN", and fixes map visibility issues when an employee is selected or during playback.

## User Review Required

> [!IMPORTANT]
> The automatic "OUT" punch on logout respects the "Physical Machine Priority" rule. If a machine punch (ZKTeco) or other authoritative punch exists within a 120-second window, the automatic punch is skipped.

## Proposed Changes

### [Component: Web Dashboard]

#### [MODIFY] [themeInterop.js](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/wwwroot/js/themeInterop.js)
- Update `applyPremiumAdminMapFilter` to:
    - Hide all markers except the selected one when `state.selectedId > 0`.
    - Hide all live markers when `state.isPlayback` is true.
- Update `updateAdminLiveStaffMap` to save the `isPlayback` state.

---

### [Component: GeoLocation Service]

#### [MODIFY] [GeoLocationService.cs](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Services/GeoLocationService.cs)
- Add a new public method `ProcessManualLogoutPunchAsync(int employeeId)`:
    - Checks if the employee has an odd number of punches for the current India business day.
    - If odd, and no authoritative punch exists within the conflict window, creates a "ManualLogout" OUT punch.
    - Synchronizes the punch to Firebase and notifies the UI.

---

### [Component: Logout Flow]

#### [MODIFY] [MobileEmployeeController.cs](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Controllers/MobileEmployeeController.cs)
- Call `_geo.ProcessManualLogoutPunchAsync(employeeId)` before ending GPS sessions in the `Logout` endpoint.

#### [MODIFY] [Logout.cshtml.cs](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Web/Payroll.Web/Areas/Identity/Pages/Account/Logout.cshtml.cs)
- Call `_geoLocationService.ProcessManualLogoutPunchAsync(employee.EmployeeID)` before ending GPS sessions in `EndEmployeeGpsSessionAsync`.

## Verification Plan

### Automated Tests
- Build both Android and Web projects.

### Manual Verification
1.  **Logout Punch**:
    *   Punch "IN" on mobile/web.
    *   Manually log out.
    *   Verify an "OUT" punch (ManualLogout) is created in the attendance logs.
2.  **Map Selection**:
    *   Open the live map.
    *   Select an employee from the list or map.
    *   Verify all other employee markers are hidden.
3.  **Playback Visibility**:
    *   Start playback for an employee.
    *   Verify that only the playback marker is visible, and all live markers are hidden.
