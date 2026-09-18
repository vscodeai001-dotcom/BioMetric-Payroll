# 1100-O Employee Conflict Resolution + Duplicate-Write Protection

## Scope

This module hardens the Firebase Employee write path without changing Employee business rules or introducing a second data authority.

## Implemented

- Per-employee process-local write gate serializes concurrent Web writes.
- New Employee ID allocation is serialized to prevent duplicate IDs from concurrent Web circuits in the same process.
- Identical repeated writes are detected by a SHA-256 fingerprint of the Employee model and become a no-op.
- Every successful Employee write carries `_revision` and `_writeId` metadata.
- Realtime Employee events carry the revision and write ID for downstream deduplication/invalidation.
- Soft-delete writes use the same revision/write metadata and publish a DELETED event.

## Conflict policy

Firebase remains the authoritative Employee store. This module does not silently merge different field edits because there is no verified field-level merge contract in the existing Employee model. Distinct writes are therefore serialized locally and represented with monotonically increasing record revisions. A later successful authoritative Firebase write supersedes an earlier one, while consumers can use `_revision` and `_writeId` to reject duplicate event processing.

Cross-process/device compare-and-swap is intentionally not claimed here because the existing FirebaseRealtimeService does not expose an ETag/transaction contract. No unsafe pseudo-CAS was introduced.

## Protected boundaries

Payroll, attendance, leave, rostering, resignation/F&F, Identity/Auth, provisioning, and destructive SQL boundaries remain unchanged.
