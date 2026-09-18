# Regularization Full Parity

## Scope
- Employee regularization request
- Admin/SuperAdmin approval/rejection
- Correction data
- Firebase realtime synchronization
- Room offline projection
- Audit trail

## Locked architecture
Firebase RTDB is the cross-platform SSOT. Web and Android are standalone Firebase clients. The existing Web/SQL attendance calculation boundary remains in place where approval must inject an AttendanceLog and recalculate the affected day.

## Request lifecycle
1. Employee creates a regularization request.
2. Firebase `owners/{ownerUid}/regularizations/{recordId}` is written first.
3. The existing Web SQL row is only a compatibility projection.
4. Android Room hydrates the Firebase request for offline/read continuity.
5. Admin/SuperAdmin approval continues through the existing controlled server boundary because it performs the existing AttendanceLog injection and AttendanceCalculatorService calculation.
6. After successful SQL attendance correction/recalculation, the final status and remarks are published back to Firebase.
7. An audit record is written to Firebase `audit_logs`.
8. Firebase realtime listeners update Web/Android without browser/app reload.

## Identity compatibility
Android-created requests use numeric negative IDs as reserved Firebase-first compatibility IDs. This avoids introducing a second SQL identity schema while preventing collision with the normal positive SQL identity sequence.

## Security
- Admin/SuperAdmin can manage regularization records.
- Employees can read only their own records.
- Employees can create only a new `Pending` request for themselves.
- Employees cannot update an existing request or change approval state.
- Existing attendance/punch and payroll calculation rules are unchanged.

## Offline
Android Room remains the local projection. Firebase listeners reconcile changes when connectivity returns. Existing offline infrastructure is preserved rather than replaced.

## Validation
- Firebase rules JSON validated.
- Modified C#/Kotlin brace checks passed.
- Full Android Gradle build could not be executed because Gradle 9.5.0 was unavailable locally and `services.gradle.org` was unreachable.
- Web .NET build could not be executed because the environment has no `dotnet` SDK installed.
