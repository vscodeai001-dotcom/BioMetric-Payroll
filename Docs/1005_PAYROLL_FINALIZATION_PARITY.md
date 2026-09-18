# 1005 Payroll Finalization Parity

## Scope
- Payroll preview
- Payroll history
- Finalization state
- Year/month state
- Calculation verification against the existing Web/SQL payroll engine

## Locked calculation boundary
The existing `PayrollProcessorService` and Web/SQL attendance/payroll calculation logic remain authoritative. Android does not calculate a second payroll result. Firebase stores the synchronized preview/finalized state so Web and Android can observe the same result without refresh.

## Firebase contract
- `owners/{ownerUid}/payroll_previews/{YYYY-MM}`: admin-only preview metadata and calculation hash.
- `owners/{ownerUid}/payroll_history/{payrollId}`: finalized payroll rows, published by the existing committed-change bridge. Employees can read only their own rows through an employeeId-constrained query.
- `owners/{ownerUid}/payroll_finalization/{YYYY-MM}`: finalized state, employee count, total net salary, preview hash, persisted hash and verification status.

## Verification
Before finalization, a canonical SHA-256 hash is calculated from the exact payroll preview values. After the SQL transaction commits, the persisted `PayrollHistory` rows are read back and hashed using the same canonical fields. `verificationStatus` is `MATCH` only when both hashes are identical. A mismatch is recorded in Firebase and logged; it never silently changes payroll values.

## Compatibility
- No EF schema change.
- No Room schema change.
- No payroll calculation rewrite.
- Existing MobileAdminPayroll API remains the controlled boundary for Android preview/finalization because it invokes the existing Web/SQL calculation engine.
- Existing UI/layout/flow preserved.
- Firebase publication is best-effort after a successful local transaction and cannot roll back committed payroll.
