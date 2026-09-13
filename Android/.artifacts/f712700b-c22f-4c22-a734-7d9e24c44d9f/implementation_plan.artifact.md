# Implementation Plan - Uniform Filters and Half Yearly Filter

Add "Half Yearly" filter to the main dashboard and ensure all screens have a uniform set of filters: "Uptodate", "Daily", "Weekly", "Monthly", "Quarterly", "Half Yearly", "Annually", and "All".

## User Review Required

> [!IMPORTANT]
> The filter names will be updated to match the requested list exactly: "Uptodate", "Daily", "Weekly", "Monthly", "Quarterly", "Half Yearly", "Annually", and "All".
> Note: "Uptodate" is used instead of "Up To Date" as per the request.

## Proposed Changes

### Core Logic

#### [MODIFY] [DateRangeUtil.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/util/DateRangeUtil.kt)
- Add support for "all" period (range from 0 to current time).
- Add support for "uptodate" (no space) to match the new uniform naming.
- Update `adjustDate` and `shiftPeriod` to handle "all".

### UI Components

#### [MODIFY] [MainActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/MainActivity.kt)
- Update the filter selection dialog to include "Half Yearly" and "All".
- Use the uniform names in the `items` array.

#### [MODIFY] [DayWiseReportActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/DayWiseReportActivity.kt)
- Update `ReportPeriod` enum to include `ALL`.
- Update the filter selection logic to include "All".
- Ensure "Half Yearly" is correctly handled in all logic blocks.

#### [MODIFY] [DetailedReportActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/DetailedReportActivity.kt)
- Update `items` array to the uniform list.
- Handle "All" period in date range calculation logic.

#### [MODIFY] [FuturePredictionActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/FuturePredictionActivity.kt)
- Update `items` array to the uniform list.
- Handle "All" period.

#### [MODIFY] [ReportsActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/ReportsActivity.kt)
- Update `items` array to the uniform list.
- Handle "All" period.

#### [MODIFY] [ShopComparisonActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/ShopComparisonActivity.kt)
- Update `items` array to the uniform list.
- Handle "All" period.

#### [MODIFY] [InvestmentRecoveryViewModel.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/viewmodel/InvestmentRecoveryViewModel.kt)
- Update `InvestmentFilterType` enum to include `ALL`.

#### [MODIFY] [InvestmentRecoveryActivity.kt](file:///E:/TeaShopPOS/app/src/main/java/com/teashop/pos/ui/InvestmentRecoveryActivity.kt)
- Update the UI to handle the new "All" filter.

## Verification Plan

### Automated Tests
- Build the application using `./gradlew assembleDebug` to ensure no errors.
- Run existing unit tests if applicable.

### Manual Verification
- Deploy to device/emulator.
- Open the main dashboard and verify the "Half Yearly" and "All" filters are present.
- Change filters on various screens and ensure they work as expected.
- Check that all filter lists are uniform across the app.
