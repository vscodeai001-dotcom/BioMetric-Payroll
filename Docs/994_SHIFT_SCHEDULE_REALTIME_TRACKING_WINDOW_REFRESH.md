# 994 - Shift Schedule Realtime Changes + Tracking Window Refresh

- FirebaseRoomHydrator already mirrors `shift_schedules` into Room.
- `LocalShiftScheduleDao` now exposes a Flow for the logged-in employee's yesterday/today schedule.
- `TrackingService` observes that Flow and re-evaluates the tracking window immediately after a shift add/change/delete reaches Room.
- A one-shot `AlarmManager.setAndAllowWhileIdle` boundary refresh wakes a waiting service at the next shift start and refreshes at the current shift end.
- Manual stop cancels the boundary alarm.
- Existing 24/7 default and attendance calculation boundary remain unchanged.
