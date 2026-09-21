# 201006 Live Staff Transient Snapshot Fix

The Admin live-staff panel previously treated a null Firebase `tracking/live` read as an empty live collection. `GetOwnerTrackingLiveAsync` intentionally returns null for both empty Firebase data and temporary transport/auth/read failures, so a transient read failure could clear the visible live list.

The panel now preserves the current live collection when the authoritative REST snapshot is unavailable. Actual employee/session removals continue to arrive through Firebase realtime child removal/session events. The requested latest `EmployeeHomeActivity.kt` replacement is also included unchanged.
