# Complete Mirroring of Web Admin/Finance Modules to Native Android

Implement the missing Admin and Finance modules in the Android application to achieve a 1:1 functional mirror of the Web application, ensuring 24/7 standalone operation and real-time bidirectional sync via Firebase.

## User Review Required

> [!IMPORTANT]
> - **Standalone Architecture**: New activities will interact directly with Firebase or `MainRepository` (which handles Room + Firebase), ensuring they work independently of the Web server's availability.
> - **Real-time UI**: All new screens will be hooked into `AdminRealtimeCoordinator` to achieve the "zero-refresh" CRUD sync requirement.
> - **UI Consistency**: Layouts will follow existing Android XML patterns but mirror the functional steps and logic found in the Blazor `.razor` components.

## Proposed Changes

### 1. Exit Management Module
Mirroring `Admin/ExitManagement.razor`.

#### [NEW] [AdminExitManagementActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/AdminExitManagementActivity.kt)
- List resignation requests with status filtering.
- Two-step workflow:
  - **Step 1: Approval**: Set approved last working day and remarks.
  - **Step 2: Settlement**: FnF Calculator showing earnings (Unpaid Salary, Leave Encash, Gratuity, Bonus) and deductions (Notice recovery, Advances, Asset recovery).
- Finalize & Terminate action.

### 2. Finance Hub Module
Mirroring `Finance/SalaryAdvancePage.razor` and `Finance/BonusEntryForm.razor`.

#### [NEW] [AdminFinanceActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/AdminFinanceActivity.kt)
- **Salary Advances**: View all pending/paid advances, record new advances for any employee.
- **Bonus Management**: record bonuses with descriptions.
- **Tax Declarations**: Approval/Rejection workflow mirroring `AdminTaxDeclarations.razor`.

### 3. FBP & Year-End Modules

#### [NEW] [FbpManagerActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/FbpManagerActivity.kt)
- Manage FBP Components (isActive, TaxExempt).
- Approve/Reject employee FBP declarations.

#### [NEW] [YearEndSummaryActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/YearEndSummaryActivity.kt)
- View year-end tax summaries.
- Consolidate button to run yearly logic.

---

### 4. Sync & Dispatcher Integration

#### [MODIFY] [RealtimeUiDispatcher.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/sync/RealtimeUiDispatcher.kt)
- Add new activities to `activityMethods` to ensure they refresh automatically:
  - `AdminExitManagementActivity` -> `loadRequests`
  - `AdminFinanceActivity` -> `loadFinanceData`
  - `FbpManagerActivity` -> `loadFbpData`
  - `YearEndSummaryActivity` -> `loadSummaries`

#### [MODIFY] [MainActivity.kt](file:///E:/Project/Android App Projects/BioMetric+Payroll/BioMetric+Payroll/Android/app/src/main/java/com/biometric/app/ui/MainActivity.kt)
- Link the new activities to the "Dashboard" or "Reports" hubs.
- Ensure `applyRolePermissions` handles visibility based on feature toggles.

---

## Verification Plan

### Manual Verification
- **Exit Workflow**: Submit resignation from Employee app -> Approve and Settle from Admin Android app -> Verify status in Firebase and Employee app.
- **Finance**: Add an advance from Admin Android app -> Verify it appears instantly in Employee app "Advances" section without refresh.
- **Real-time Sync**: Open Admin app on one device and Employee app on another. Modify an advance/leave -> Verify UI updates instantly on the other device.
- **Offline Tracking**: Verify `TrackingService` continues to log points while performing these admin actions.
