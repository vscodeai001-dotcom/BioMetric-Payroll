# 983 - Employee Provisioning + Firebase Identity Binding

## Contract

Employee Android login is accepted only when all of the following agree:

1. Firebase Authentication successfully verifies the supplied password.
2. The Firebase ID token contains `role=Employee`/`Staff`.
3. The ID token contains a positive `employee_id`.
4. The ID token contains a non-empty `owner_uid`.
5. `owners/{owner_uid}/employees/{employee_id}` exists.
6. The employee row's `employeeId` matches the claimed employee ID.
7. If the employee row has an email, it matches the authenticated Firebase email.
8. The employee row is active (`isActive != false`).
9. The single-device `employee_sessions/{firebaseUid}` record is acquired by this device.

Android does not guess the tenant, employee ID, or email binding and does not create Firebase Auth users from the client.

## Lifecycle

Web/Admin provisioning remains responsible for creating the Firebase Auth identity and assigning the canonical claims. Android verifies the resulting identity and employee row before creating its local session.

An inactive, deleted, mismatched, or incompletely provisioned employee is rejected before the single-device session is established.

## Existing business logic

No payroll, attendance, leave, shift, tax, FBP, or calculation rules were moved into this verifier. It is an identity/provisioning boundary only.

## Verification

Kotlin brace/static checks passed for the modified files. A Gradle compile could not be completed because the environment attempted to download Gradle 9.5.0 from `services.gradle.org`, which was unreachable (`UnknownHostException`).
