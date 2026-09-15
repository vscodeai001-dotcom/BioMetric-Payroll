v15 Firebase Realtime Database Rules

Modified file:
Android/database.rules.json

Purpose:
- Keep employee GPS live/history writes available without Neon/Render.
- Allow employee-scoped writes for employee/attendance/punch/regularization/leave/resignation records.
- Keep Admin/SuperAdmin access to owner read-model data.
- Remove the broad owners/$uid .read/.write permission that would otherwise override child restrictions.

Deployment:
Firebase Console -> Realtime Database -> Rules -> replace the rules with database.rules.json -> Publish.

Do not change Android layouts, Room schema, payroll calculations, or Web business logic.
