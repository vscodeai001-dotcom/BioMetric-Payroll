# 991 - Tracking History + Offline Tracking Details Parity

## Scope

This pass adds owner-scoped Firebase tracking-history reconciliation to the existing Android offline tracking screen without replacing the local Room GPS ledger.

## Data flow

`Android GPS -> Room local ledger -> Firebase owner-scoped tracking/history -> Admin/SuperAdmin read path`

The local queue remains authoritative for unsynchronized device events. Firebase history is read-only from the history viewer and is not used to delete pending Room records.

## History read contract

- Path: `owners/{ownerUid}/tracking/history/{employeeId}`
- Read is performed only after Firebase authentication and owner resolution.
- Each history child is validated against the requested EmployeeId.
- Results are deduplicated using child key + timestamp + coordinates.
- History is sorted by parsed tracking timestamp.
- The viewer limits the read to the most recent 2,000 points.

## Offline safety

Pending Room records are never deleted because a Firebase history query is empty or temporarily unavailable. Existing `OfflineSyncWorker` remains responsible for retrying pending uploads and marking records only after a successful Firebase write.

## Role isolation

When the current Android session is Employee/Staff, the viewer ignores an arbitrary `employeeId` Intent extra and uses the session's own employee ID. Admin/SuperAdmin may supply a selected employee ID through the protected screen integration.

## Reconnect / duplicate handling

The history reader performs in-memory deduplication for repeated Firebase snapshots or duplicate-equivalent points. The existing client event ID remains the primary idempotency key for writes.

## Historical data preservation

This module does not delete, rewrite, or recalculate attendance, payroll, leave, or employee history. GPS history remains separate from payroll/business records.
