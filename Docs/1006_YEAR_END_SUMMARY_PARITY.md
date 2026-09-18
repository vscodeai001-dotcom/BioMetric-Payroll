# 1006 Year-End Summary Parity

## Scope
- Year-end calculations
- Summary viewer
- Finalization state
- Historical data integrity

## Calculation boundary
The existing `YearEndSummaryService` and Web/SQL payroll history remain authoritative for annual aggregation. Android does not calculate a second annual payroll result.

## Firebase contract
- `owners/{ownerUid}/year_end_summaries/{employeeId}_{taxYear}` stores the committed annual summary.
- Fields include the existing summary values plus `state`, `integrityHash`, `calculationSource`, and `publishedAtUtc`.
- Publication occurs only after the existing SQL consolidation has committed.
- The Web summary viewer reads annual financial rows from Firebase SSOT.
- Employee identity names continue to use the existing employee projection solely for display labels.
- Android Admin/SuperAdmin keeps the Firebase year-end collection synchronized for realtime availability without introducing a Room schema migration.

## Historical integrity
Each summary has a deterministic SHA-256 hash over employee, tax year, gross taxable salary, TDS, employee PF, absent days and overtime pay. The hash is informational integrity metadata and does not alter the existing calculation.

## Compatibility
- No EF schema change.
- No Room schema change.
- No payroll calculation rewrite.
- Existing SQL annual aggregation is preserved.
- Existing UI/layout/flow is preserved.
- Firebase publication is best-effort after successful SQL commit and cannot roll back committed annual data.
