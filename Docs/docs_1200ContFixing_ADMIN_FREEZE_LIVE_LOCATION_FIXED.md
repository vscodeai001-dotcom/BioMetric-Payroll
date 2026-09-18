# 1200ContFixing Admin Freeze + Live Location Fix

BASELINE: latest user-supplied `1200ContFixing`.

The existing 1200 live-location rendering implementation is preserved,
including `lastRenderedLiveSignature` deduplication and the existing
owner-scoped Firebase/SignalR compatibility logic.

Applied targeted fixes:
- Admin/SuperAdmin cannot recover/start Employee GPS tracking.
- Boot and notification tracking recovery are Staff/Employee only.
- TrackingService has a defensive role boundary.
- Admin login stops any inherited TrackingService.
- Hidden Command Center OSMDroid map is initialized only when Tracking hub is opened,
  reducing startup CPU/GPU contention and ANR risk.

No payroll calculations, attendance business rules, database schema, or screen flow
were intentionally changed.
