# 1200-J Shift Schedule Integration

## Scope

Shift schedules are treated as Firebase Realtime Database SSOT inputs for Web and Android attendance. The existing `ShiftSchedule` domain model and `AttendanceCalculatorService` remain unchanged.

## Implemented

- Admin Web scheduler reads employees and schedules from Firebase.
- Admin mobile shift GET/CREATE/DELETE uses the Firebase shift service instead of direct SQL schedule CRUD.
- Employee mobile shift GET uses the Firebase shift service.
- Concrete Firebase shift create/delete immediately reconciles the affected DailySummary through `ShiftScheduleAttendanceImpactService`.
- Pattern generation writes concrete schedules to Firebase and reconciles each generated employee/date attendance summary.
- Existing manual attendance overrides are preserved by the attendance impact service.
- Existing SQL database remains a compatibility/calculation write model through the Firebase sync bridge.

## Boundary

`AttendanceCalculatorService` remains the calculation authority. This module does not replace payroll/attendance formulas or alter database schema.

## Known compatibility boundary

Legacy scheduled-shift consumers inside complex attendance/payroll calculations may still read the local SQL compatibility projection. The Firebase-to-local sync bridge remains responsible for keeping that projection synchronized. Immediate schedule-change reconciliation reads the changed schedule from Firebase so a newly-created or deleted schedule does not have to wait for the background compatibility sync loop.
