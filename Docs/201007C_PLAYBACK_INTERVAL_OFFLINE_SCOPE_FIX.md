# 201007C Playback / Offline Scope Fix

## Scope
Presentation and data-loading refinement only. Existing database schema, attendance calculations, GPS/session business rules, and application flow are preserved.

## Changes
- Route Playback uses the administrator-configured GPS capture interval: 30 seconds, 1 minute, 2 minutes, or 5 minutes.
- Historical storage is not deleted or rewritten. Playback/table presentation is sampled at the configured interval, keeps the first sample, and always keeps the final recorded point.
- Playback ordering prefers original `CapturedAtUtc` and falls back to legacy `RecordedAtUtc`.
- Offline Tracking and Offline Tracking Details are scoped to employees/sessions that actually have `OfflineSync` GPS evidence in the selected filter/date range.
- Offline session scoping is resolved from the full filtered query before the point display cap, so employees are not missed only because the 2000-row presentation cap is reached.
- Existing map control presentation and collision-offset behavior remain unchanged.
