# 1003 Bonus Management Full Parity

## Architecture
- Firebase Realtime Database is the Bonus SSOT.
- Web and Android remain standalone Firebase clients.
- Existing UI, model fields, payroll calculations and screen flow are preserved.
- Room remains the Android offline/local projection.

## Implemented
- Web Admin bonus reads/writes/deletes use FirebaseBonusService.
- Web Admin employee selector uses Firebase employees.
- Web Employee My Bonuses uses the authenticated `employee_id` claim and Firebase child-scoped query.
- Android Employee bonus reads are constrained by `employeeId` at Firebase query time.
- Android Admin existing Firebase bonus create/list path remains connected.
- Firebase rules now explicitly support employee-scoped bonus reads while keeping writes admin-only.
- Paid/payroll-attached bonus deletion is protected.
- Bonus Firebase transport key is `[NotMapped]` and does not alter the EF schema.

## Required verification
1. Web Admin creates bonus -> Android sees it without refresh.
2. Android Admin creates bonus -> Web sees it without refresh.
3. Employee sees only own bonuses.
4. Offline Android cache refreshes after reconnect.
5. Payroll-attached bonus cannot be deleted.
6. Existing payroll bonus calculation remains unchanged.

Full Gradle/.NET builds are environment-dependent and must be run in the user's build environment.
