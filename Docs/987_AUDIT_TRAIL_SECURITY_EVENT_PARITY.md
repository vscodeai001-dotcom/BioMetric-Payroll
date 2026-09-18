# 987 — Audit Trail + Security Event Parity

## Contract

Android audit writes now use the same owner-scoped Firebase Realtime Database projection used by the payroll sync layer:

`owners/{ownerUid}/audit_logs/{logId}`

The previous standalone Firestore `auditLogs` write path was removed from `AuditLogger`; it could create a second audit store that was not visible to the existing Android audit reader.

## Actor identity

Each Android audit event records:
- `userId` from Firebase Auth
- `actorRole` from the refreshed Firebase ID token
- `ownerUid` from the session/canonical owner claim
- `targetId` when supplied
- timestamp

## Secret handling

Audit payloads redact password, token, authorization, API-key and secret fields and are length bounded. Authentication credentials and bearer tokens are never intentionally copied into the audit record.

## Realtime Database authorization

`audit_logs` has an explicit rule before the generic admin-table rule. Admin/SuperAdmin users can read the owner's audit trail. A non-admin authenticated user may only append an event whose `userId` equals their own Firebase UID. The owner UID and log ID are validated against the path.

## Compatibility

The existing Room `local_audit_logs` schema remains unchanged in this pass to avoid an unverified destructive migration. The additional actor/tenant metadata is retained in Firebase, which is the audit viewer's live source. Existing payroll, attendance and calculation logic is untouched.

## Security boundary

Client-side audit logging is a trace/projection, not a replacement for server-side authoritative audit records. Web/Server operations remain responsible for authoritative audit events where the existing Web application already owns the transaction.
