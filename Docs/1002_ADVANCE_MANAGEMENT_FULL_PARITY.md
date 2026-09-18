# 1002 Salary Advance Full Parity

## Architecture
- Firebase Realtime Database remains the centralized SSOT.
- Web and Android remain standalone clients.
- No Neon, Npgsql, Render API, or Android -> Web dependency is introduced.
- Existing SalaryAdvance model, screens, filters, status display, and payroll calculations are preserved.

## Implemented
### Web Admin
- Salary Advance feature gate reads from Firebase.
- Active employee selector reads from Firebase.
- Add Advance writes directly to `owners/{ownerUid}/advance_payments`.
- Unpaid and history filters read from Firebase.
- Delete removes the Firebase record, with paid advances protected.
- Existing realtime change notification remains in place.

### Web Employee
- Employee identity is resolved from the authenticated `employee_id` claim.
- Advance history is employee-scoped from Firebase.
- Feature and employee-view permissions are read from Firebase.
- No legacy SQL read is used by this screen.

### Android
- Existing employee advance request writes to Firebase.
- Existing employee advance history reads Firebase and refreshes through the realtime change path.
- Existing Admin Finance Firebase repository continues to read/create advances from Firebase.
- Existing Room `local_advance_payments` remains the offline projection.

### Firebase rules
- Admin/SuperAdmin can manage advance records.
- Employee collection reads require an employee-scoped `orderByChild=employeeId` + `equalTo` query.
- Employee cannot write advance records.
- Feature settings can be read by authenticated employee sessions but remain admin-write only.
- Advance records require positive numeric amount, numeric date, employeeId and recovery state.

## Compatibility
- Added `SalaryAdvance.FirebaseKey` as `[NotMapped]` transport metadata only.
- UUID advance records created by Android remain addressable by Web.
- Numeric legacy advance IDs remain supported.
- Payroll calculations were not rewritten in this module.

## Validation
- JSON rules parsed successfully.
- Modified C#/Razor/Kotlin files passed structural delimiter checks.
- Full Android Gradle build remains environment-dependent because Gradle distribution download may be unavailable.
- Full Web .NET build remains environment-dependent when the SDK is unavailable.

## Next verification
1. Web Admin add -> Android realtime list.
2. Android employee request -> Web Admin realtime list.
3. Web delete unpaid -> Android disappears without reload.
4. Paid advance remains protected.
5. Offline Android request -> reconnect -> Firebase -> Web.
6. Employee A cannot read Employee B advances.
7. Existing payroll deduction remains unchanged.
