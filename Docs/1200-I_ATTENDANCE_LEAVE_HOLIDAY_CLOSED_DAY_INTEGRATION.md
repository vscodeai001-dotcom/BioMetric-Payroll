# 1200-I Attendance Leave / Holiday / Closed-Day Integration

- Leave mutations remain SQL/EF authoritative and are projected to Firebase `leave_requests`.
- Approved leave immediately refreshes the affected DailySummary and projects it to Firebase.
- Leave revocation/deletion refreshes the affected DailySummary and projects the change.
- Company holiday create/delete is projected to Firebase `shop_closed_days`.
- Holiday changes recalculate affected employee DailySummary records using the existing AttendanceCalculatorService.
- Existing manual overrides are preserved during holiday impact recalculation.
- Firebase application-change events are published after successful projection writes.
- No attendance formulas or database schema were changed.
