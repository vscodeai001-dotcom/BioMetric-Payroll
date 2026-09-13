# Fix Custom Filter Inconsistency and UI Issues

This plan addresses the "Incorrect Result" and "Hidden Date Label" issues in the Dashboard and Detailed Report screens.

## User Review Required

> [!IMPORTANT]
> The navigation arrows (Prev/Next) will now be hidden when the "Custom" filter is active, as they don't have a logical "next range" behavior and their removal prevents accidental navigation out of the custom range.
> Clicking on the date range label while in "Custom" mode will now re-open the Date Range Picker for easier adjustments.

## Proposed Changes

### [Core Logic]

#### [MODIFY] [DateRangeUtil.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/util/DateRangeUtil.kt)
- Fix `getRangeForPeriod` to ensure `endDate` is only used when the period is specifically "Custom". This prevents leftover `endDate` values from polluting other filter types (Daily, Monthly, etc.), which was the primary cause of incorrect results when switching between filters.
- Ensure proper time normalization (00:00:00 to 23:59:59) for custom ranges.

---

### [Dashboard (Main Activity)]

#### [MODIFY] [MainActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/MainActivity.kt)
- Update `updateFilterButtonText` to hide the Prev/Next date arrows when "Custom" filter is active.
- Update `tvDateLabel` click listener to re-open the Date Range Picker if the current filter is "Custom".

---

### [Detailed Report]

#### [MODIFY] [DetailedReportActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/DetailedReportActivity.kt)
- Fix the filter selection logic to clear `customEndDate` when switching to a non-custom filter.
- Update `onCreate` and `refreshReport` to ensure the initial custom range (passed from Dashboard) is properly normalized before loading data.
- Hide/Show navigation arrows based on whether the filter is "Custom".
- Update `tvDateLabel` click listener to re-open the Date Range Picker if the current filter is "Custom".

## Verification Plan

### Automated Tests
- N/A (Manual verification on device is preferred for UI/Result accuracy)

### Manual Verification
1. Open the Dashboard and select "Custom" filter. Pick a range (e.g., Aug 1 to Aug 5).
2. Verify the "NET GLOBAL PROFIT" matches the expected sum of shops for that period.
3. Observe that Prev/Next arrows are hidden and the date range is clearly displayed.
4. Click "Global Insights" to open the Detailed Report.
5. Verify the "Net Result" in the Detailed Report matches the Dashboard's "NET GLOBAL PROFIT".
6. Switch to "Daily" filter in the Detailed Report and verify it shows only ONE day's data correctly.
7. Switch back to "Custom" and verify it re-prompts or correctly restores the range.
