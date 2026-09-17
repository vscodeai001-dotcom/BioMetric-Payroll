# 992 - GPS Reconnect, Idempotency & Tracking Session Lifecycle

## Scope
This pass hardens Android GPS delivery across network loss/reconnect without changing payroll or attendance calculations.

## Guarantees
- Every GPS point has a client-generated `clientEventId` and positive per-session `Sequence`.
- Firebase history uses `clientEventId` as the immutable event key, making retries idempotent.
- Owner-scoped live location is transaction-protected so an older queued point cannot replace a newer point in the same session.
- A session is started idempotently. A session already marked `ENDED` cannot be resurrected by a retry.
- Session end is idempotent and writes the terminal `ENDED` state.
- Pending Room rows remain durable until the Firebase write succeeds.
- Historical GPS points from an ended session may still be uploaded as history; they cannot revive the live marker.
- Existing Web/Render compatibility paths remain populated where accepted, but the owner-scoped Firebase path is the Android security boundary.

## Session lifecycle
```text
START -> ACTIVE -> GPS events -> END
                         |
                         +-- reconnect/retry does not duplicate history
                         +-- late old point cannot replace newer live point
                         +-- late START cannot resurrect END
```

## Deliberately unchanged
- GPS capture interval and existing background service behavior.
- Payroll calculations.
- Attendance calculations and Web business rules.
- Employee/tenant provisioning rules.
