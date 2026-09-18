# 1200-C Error and Warning Fix Report

## Fixed in this package

1. Nullable `EmployeeID` assignment in `MobileAdminPayrollController` history DTO mapping.
2. Nullable `ManualLeaveDays` assignment in the same history DTO mapping.
3. `DateTime` to `DateOnly?` conversion in `FirebaseEmployeeManagementService` Unix date mapping.
4. `DateTime` to `DateOnly?` conversion in `FirebaseAttendanceService` Unix date mapping.
5. Nested target-typed conditional expressions in `FirebaseEmployeeHistoryService` were rewritten with explicit nullable result types for `int?`, `long?`, `decimal?`, and `bool?`.
6. Nullable `JsonElement?` arguments passed after an established `HasValue` guard in `FirebaseSqliteSyncService` now use `.Value` explicitly.
7. Pending punch projection now filters out records without an EmployeeID before constructing the non-nullable DTO.
8. Firebase Employee authentication null-state check was hardened so a nullable Employee cannot be dereferenced on the Admin path.

## Not changed without source evidence

- `GoogleCredential.FromFile(string)` warning: the uploaded 1200-C source tree contains no occurrence of this API, so there is no grounded source location to modify.
- Decimal-to-double diagnostics: the uploaded source tree did not contain a matching decimal argument to a double-only API at the reported locations. No blind casts were introduced.
- Async-without-await diagnostic: no async method in the uploaded source tree was found whose method body lacks an await.

## Verification limitations

The environment has no `dotnet` SDK, so a real C# compiler/build cannot be run here. Structural checks and targeted source checks were performed instead.
