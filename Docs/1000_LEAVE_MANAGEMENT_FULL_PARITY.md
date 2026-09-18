# 1000 - Leave Management Full Parity

## Scope

This module brings the native Android Leave Management flow into parity with the existing Web leave behavior while keeping the Web/SQL business rules authoritative for approval, balance mutation, attendance recalculation, notifications, and deletion.

## Employee self-service

- Leave Management feature gate is read from Firebase `feature_settings/1`.
- Employee request permission follows the Web hierarchy: `EnableLeaveManagement` + `EmployeeCanViewLeave`.
- Leave types now match the Web employee request screen: Paid Leave, Sick Leave, Vacation, Other.
- Start date and end date are supported.
- Half-day and reason/notes are preserved.
- Multi-day submission is one client-side Firebase multi-location batch instead of one network operation per day.
- Duplicate protection checks employee + leave date. Existing requests block a new request, matching the Web duplicate rule.
- Room is updated before the Firebase write so the employee can see a submitted request while offline.
- Firebase Realtime Database disk persistence queues the batch while offline and retries it after reconnect.
- Employee leave history is projected from Room, so Firebase -> Room realtime updates immediately refresh the list.
- Leave balances continue to come from the canonical Firebase employee record. No artificial balance calculation was added on Android.

## Firebase / SQL compatibility

The Web `LeaveRequest` model has an integer `LeaveRequestID`. Android-created Firebase requests therefore use deterministic negative integer IDs derived from employee + date. This avoids introducing a schema migration and keeps Firebase -> Web SQLite/PostgreSQL projection compatible with the existing integer primary key.

The Web Firebase transport mapping was corrected so `IsApproved == false` is exported as `Pending`, not `Rejected`. This matches the actual Web service semantics: a pending request is `IsApproved == false`, approval sets it true, denial deletes the request, and revoking an approval sets it false again.

## Admin / SuperAdmin

- Direct activity access is restricted to Admin/SuperAdmin.
- Pending requests can be approved or denied.
- Deny uses the existing Web delete endpoint, matching the Web `OnDeny -> DeleteLeaveRequest` behavior.
- Approved requests can be revoked using the existing status endpoint with `Approved=false`.
- Automatic `Loss of Pay (Auto)` records can be deleted, matching the Web action.
- Admin-created leave remains API-backed so the existing Web service performs auto-approval and DailySummary refresh.
- Existing Web business logic for paid/sick balance deduction remains authoritative and unchanged.

## Realtime behavior

1. Employee creates leave -> Room local projection -> Firebase `leave_requests`.
2. Firebase Room hydrator mirrors the request to Android Room.
3. Web Firebase sync projects the request into the existing SQL compatibility store.
4. Admin approval/revocation/deletion continues through the existing Web service/API so balance and attendance side effects remain intact.
5. Web SQL changes are exported back to Firebase.
6. Android hydrator receives the Firebase change and updates Room/UI.

## Intentionally not changed

- Existing Web `LeaveRequest` database schema.
- Existing leave balance deduction rules.
- Existing DailySummary/attendance calculation logic.
- Existing accrual and sandwich-rule business calculations.
- Existing Web authorization model.
- Existing Firebase owner-node architecture.

## Validation

- Kotlin source delimiter counts were checked for all modified Kotlin files.
- The modified leave-management XML parses successfully.
- Full Gradle compilation was attempted but could not run because the environment attempted to download Gradle 9.5.0 from `services.gradle.org` and network access was unavailable (`UnknownHostException`).
