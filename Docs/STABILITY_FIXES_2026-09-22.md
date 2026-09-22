# BioMetric Payroll Stability Patch - 2026-09-22

Applied to the supplied 220926146 project snapshot.

## Android
- MainViewModel workforce recalculation is debounced during Firebase startup hydration.
- Normal coroutine cancellation is no longer logged as a workforce failure.
- The full-screen Workforce loader is now startup-only and is not shown for realtime updates or filter recalculation.
- Existing dashboard data remains visible while Firebase realtime snapshots settle.

## Web
- Generic Firebase startup seeding now skips AttendanceLog/owner-wide attendance snapshots.
- Attendance punches are read through an indexed timestamp date-range query instead of downloading the complete attendance_punches collection for every date-window request.
- Realtime Database rules add `timestamp` to `attendance_punches` indexes.
- Duplicate DailySummary rows for the same employee/date no longer crash AttendanceLogViewer's fingerprint seeding. The newest SummaryID is retained.
- SQLite compatibility connection uses shared cache and a 30-second default busy timeout to reduce transient `database is locked` failures.
- Attendance worker startup hydration no longer downloads the complete owner attendance ledger. ZKTeco remains the operational punch source and new punches continue publishing incrementally.

## Firebase
- The previously deployed RTDB rules remain the source of truth. The supplied rules file now also indexes `attendance_punches.timestamp` for the bounded date-range reads above.
- Run `firebase deploy --only database` after taking the patched rules file.

## Validation
- `Android/database.rules.json` parses as valid JSON.
- Local environment did not contain the .NET SDK.
- Android Gradle wrapper attempted to obtain Gradle 9.5 but the validation environment has no network access, so a full Android Gradle compile could not be completed here.
