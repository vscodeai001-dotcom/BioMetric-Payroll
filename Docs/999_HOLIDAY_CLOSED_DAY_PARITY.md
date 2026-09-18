# 999 - Holiday / Closed-Day Management Parity

- ShopClosedDay remains the synchronized source for shop closure dates.
- FirebaseRoomHydrator preserves `affectedEmployeeIds` instead of dropping the list during projection.
- Empty affectedEmployeeIds means all staff, matching the existing Android Holiday Management UI.
- Employee attendance manual punch is blocked on an applicable closed day.
- Automatic attendance guard supports the same closed-day decision.
- TrackingWindowResolver treats an applicable closed day as a non-tracking window.
- TrackingService observes Room closed-day changes and refreshes immediately.
- SalaryEngine continues to receive closedDays and its existing payroll rules are untouched.
- Holiday/closed-day management UI is explicitly Admin/SuperAdmin only.
- No payroll or attendance calculation was duplicated in Android.
