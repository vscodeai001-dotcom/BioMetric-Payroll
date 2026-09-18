# Critical Stability and UI Fixes Plan

This plan addresses several critical issues including crashes, deserialization errors, session management bugs, and edge-to-edge UI overlaps.

## User Review Required

> [!IMPORTANT]
> **Edge-to-Edge Changes**: I am moving from hardcoded layout padding to dynamic window inset handling. This will ensure the app looks consistent across devices with different taskbar heights (gesture vs. button navigation).
> **Session Handling**: If a Firebase session is lost but the app thinks it's logged in, the user will now be prompted to log in again instead of seeing a "session missing" error.

## Proposed Changes

### [Sync & Data]

#### [MODIFY] [FirebaseSyncManager.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/sync/FirebaseSyncManager.kt)
- Add `Gson` initialization for robust JSON serialization of complex `AuditLog` values.
- Update `decodeAuditLog` to convert `HashMap` or other complex objects in `oldValue`/`newValue` to JSON strings instead of returning `null`.

### [Authentication & Session]

#### [MODIFY] [LoginActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/LoginActivity.kt)
- Enhance `setupUI` to verify the `FirebaseAuth` current user.
- If `mobileSessionStore` reports an active session but Firebase does not, reset the local session state to allow a fresh login.

### [UI & Layout Stability]

#### [MODIFY] [EmployeeHomeActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/EmployeeHomeActivity.kt)
- Audit and fix all `binding` property accesses in asynchronous callbacks.
- Replace non-nullable `binding` with safe-calls to `_binding` in map layout listeners and post-execution blocks to prevent `NullPointerException` during activity destruction.

#### [MODIFY] [MotionBaseActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/MotionBaseActivity.kt)
- Refine `applyWindowInsets` to dynamically detect scrolling containers (e.g., `NestedScrollView`) and apply bottom navigation insets as padding.
- This ensures content is never obscured by the system taskbar while maintaining a "scrolling under" effect.

#### [MODIFY] [activity_main.xml](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/res/layout/activity_main.xml)
- Remove hardcoded `paddingBottom="90dp"`.
- Ensure `clipToPadding="false"` is set on the main scrolling container to allow full scroll range.

### [Styling & Aesthetics]

#### [MODIFY] [bg_premium_card.xml](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/res/drawable/bg_premium_card.xml)
- Refine stroke and corner radius for a more premium look.
- Ensure colors are fully reactive to Dark/Light mode transitions using `?attr/colorSurface`.

## Verification Plan

### Automated Tests
- Run Gradle build to ensure no compilation errors: `./gradlew assembleDebug`
- Unit tests for `FirebaseSyncManager.decodeAuditLog` (if applicable) to verify complex object serialization.

### Manual Verification
- **Session**: Log out from Firebase Console and verify `LoginActivity` correctly resets to Login mode.
- **Scrolling**: Scroll to the bottom of the Workforce Hub on a device with button navigation and verify no content is obscured.
- **Crash**: Rapidly rotate `EmployeeHomeActivity` while the map is loading to verify no NPE occurs.
