# 993 - Tracking Start/Stop + Shift/Working-Hours Lifecycle Parity

## Scope

This pass adds a shift-aware tracking-window resolver without changing payroll or attendance calculation rules.

## Modes

Android supports three tracking modes at the lifecycle layer:

- `24/7`: existing behavior and the default. No deployment silently changes to shift-only tracking.
- `SHIFT`: tracking is allowed only during the employee's assigned `shift_schedules` window.
- `CUSTOM`: tracking is allowed during `tracking_custom_start` / `tracking_custom_end` stored in the existing `tracking_prefs` preference store.

No new authoritative Web setting was invented. The default remains `24/7` until an existing settings flow explicitly writes a supported mode.

## Shift boundaries

`LocalShiftScheduleDao` resolves today's and yesterday's schedules. A shift whose end time is equal to or earlier than its start time is treated as an overnight shift and ends on the following calendar day.

Example:

`22:00 -> 06:00` becomes `today 22:00 -> tomorrow 06:00`.

This is intentionally aligned with the existing Android attendance engine's midnight-shift handling rather than creating a second attendance calculation implementation.

## Lifecycle

When the service receives `ACTION_START`:

1. Validate the existing authenticated employee session.
2. Resolve the tracking mode/window.
3. If outside the window, end the current GPS session with `OUTSIDE_TRACKING_WINDOW`, stop location updates, and leave recovery enabled.
4. If inside the window, create/use the current GPS session and start location updates.
5. Existing Firebase session/history idempotency remains unchanged.

When the live attendance policy disables Geo-Fencing, tracking stops with `GEO_FENCING_DISABLED` and recovery is disabled.

When the user explicitly logs out, tracking stops with `LOGGED_OUT` and recovery is disabled.

## Recovery

`TrackingRecoveryWorker` resolves the current window before restarting the service. If the employee is outside the window it leaves the service stopped and schedules another check. WorkManager's existing minimum periodic cadence remains unchanged.

Boot/package-replacement recovery starts immediately only for `24/7`; `SHIFT` and `CUSTOM` modes rely on the persisted WorkManager recovery path so the window can be evaluated using Room safely.

## Data safety

No payroll, attendance, leave, shift calculation, or salary formula was moved into the tracking lifecycle layer. Historical GPS events remain durable in Room/Firebase and are not deleted merely because the service leaves a tracking window.
