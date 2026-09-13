# Zero-Flicker Dashboard Implementation Plan

This plan addresses the "Zero-Flicker" requirement for the Dashboard and Reports. It ensures that "0.00" metrics are never shown briefly during loading, and transitions directly from the loader to real data.

## User Review Required

> [!NOTE]
> The "Zero-Flicker" logic has been implemented by extending the existing "Strict Profit Check" pattern from `MainActivity` to `DetailedReportActivity` and `ReportsActivity`. Additionally, "Optimistic UI" (caching) has been added to `ReportsViewModel` to provide instant results when opening reports.

## Proposed Changes

### [Report Logic & UI]

#### [MODIFY] [ReportsViewModel.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/viewmodel/ReportsViewModel.kt)
- Added "Optimistic UI" support to `reportData` by loading from `MainRepository`'s list cache on start.
- Updated `isLoading` logic to handle cached data, preventing unnecessary loading states if data is already available.

#### [MODIFY] [DetailedReportActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/DetailedReportActivity.kt)
- Implemented "Strict Profit Check": The loader now stays active if the net result is zero while the ViewModel is still loading.
- This prevents the "0.00" flicker that occurs when the UI layout is shown before the first data emission is processed.

#### [MODIFY] [ReportsActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/ReportsActivity.kt)
- Applied the same "Strict Profit Check" logic to ensure consistency across all reporting screens.

## Verification Plan

### Automated Tests
- N/A (UI-centric transition logic)

### Manual Verification
1. Open the App: Observe the "Brewing" loader in `MainActivity`. Verify it transitions directly to metrics without a "0.00" flicker.
2. Open Detailed Report: Select a shop and period. Verify the loader persists until metrics are non-zero (or loading completes).
3. Re-open Report: Verify the report opens instantly using cached data (Optimistic UI).
