# Implementation Plan - Bug Fixes and UI Enhancements

This plan addresses three issues: fixing a crash when updating dual shift times, fixing the shop switcher in the Future Prediction screen, and hiding the shop switcher icon in the Dashboard.

## Proposed Changes

### 1. Fix Dual Shift Time Crash
The `showTimePicker` function in `StaffActivity.kt` and `AddStaffDialogFragment.kt` crashes when attempting to parse an empty or invalid time string using `toInt()`. We will replace `toInt()` with `toIntOrNull()` and provide default values.

#### [MODIFY] [StaffActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/StaffActivity.kt)
- Update `showTimePicker` to safely parse hours and minutes.

#### [MODIFY] [AddStaffDialogFragment.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/AddStaffDialogFragment.kt)
- Update `showTimePicker` to safely parse hours and minutes.

---

### 2. Fix Future Prediction Shop Switcher
The Future Prediction screen is missing the shop switcher functionality. We will add the shop selection dialog trigger to the toolbar click and ensure the `btnShop` in the dual header is functional by adding the activity to `MotionBaseActivity`'s shared ViewModel resolution logic.

#### [MODIFY] [MotionBaseActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/MotionBaseActivity.kt)
- Add `FuturePredictionActivity` to the `sharedVM` resolution list in `setupDualHeader`.

#### [MODIFY] [FuturePredictionActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/FuturePredictionActivity.kt)
- Add toolbar click listener to trigger the shop selection dialog.
- Implement `showShopSelectionDialog` to use `GlobalSwitcherDelegate`.

---

### 3. Hide Shop Switcher in Dashboard
Hide the shop switcher icon (`btnShop`) in the Dashboard's toolbar header as requested.

#### [MODIFY] [ShopDashboardActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/ShopDashboardActivity.kt)
- Update `updateToolbarTitle` to hide the `btnShop` toggle button from the `HeaderRefs` returned by `setupDualHeader`.

## Verification Plan

### Manual Verification
1. **Dual Shift Crash:**
   - Go to Staff screen.
   - Edit a staff member or add new.
   - Enable "Split Shift".
   - Click on "Shift 2 Start" or "Shift 2 End".
   - Verify that the time picker opens without crashing.

2. **Future Prediction Shop Switcher:**
   - Go to Future Prediction screen.
   - Click on the toolbar title.
   - Verify that the shop selection dialog appears.
   - Select a different shop and verify data refreshes.
   - Click the shop icon in the toolbar (if visible) and verify it works.

3. **Dashboard Shop Switcher:**
   - Open the Shop Dashboard.
   - Verify that the shop switcher icon (the one next to the theme toggle) is hidden.
   - Verify that the title click still works (if intended) or is also disabled if required. *Note: The user said "hide shop switcher icon", so we will prioritize hiding the icon.*
