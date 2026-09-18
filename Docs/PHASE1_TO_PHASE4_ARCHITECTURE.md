# BioMetric Payroll - Phase 1 to Phase 4 Implementation

## Selected architecture

Web SQL + Firebase synchronization + Android Firebase/Room.

- Existing Web SQL/EF Core remains the compatibility/business-calculation store during the phased migration.
- Firebase Realtime Database is the shared cross-platform synchronization source.
- Firebase Authentication owns identity and employee single-device session state.
- Android Room is a local offline/cache projection, not a competing source of truth.
- Android Employee self-service reads/writes Firebase directly.
- Existing Web CRUD changes continue to synchronize to Firebase through the existing interceptor/bridge.
- Firebase changes are projected back into Web SQL so existing Web business logic and screens continue to work.

## Phase 1 - Web SQL + Firebase sync foundation

Preserve the existing Web application and business calculations while keeping the Firebase bridge bidirectional.

- Firebase owner table contract remains centralized in FirebaseRealtimeService.
- FirebaseSqliteSyncService remains the Web compatibility bridge.
- Existing Web SQL writes continue to publish Firebase changes.
- Firebase changes continue to hydrate/update the Web SQL projection.
- Existing Worker SQLite compatibility initialization remains non-destructive.

## Phase 2 - Employee Firebase/Room parity

The native Android Employee module uses Firebase directly for self-service data.

Covered modules in the current baseline:

- Dashboard / Profile
- Attendance
- Payslip
- Leave
- Advance
- Bonus
- Regularization
- Resignation
- Tax
- FBP
- Shift
- Firebase single-device session
- GPS / geofence tracking

Room receives Firebase records for offline UI/cache operation. Firebase child deletions now also remove the corresponding Room cache row for the core employee/self-service tables.

## Phase 3 - Admin parity foundation

Existing Android Admin screens are retained. Shared Admin data already flows through MainRepository/FirebaseRoomHydrator where available. Complex payroll/attendance business calculations remain compatible with the Web/SQL calculation layer during this phase rather than being rewritten or duplicated.

The Admin module can therefore be migrated screen-by-screen without changing the Web calculation rules.

## Phase 4 - SuperAdmin and full cross-platform verification

SuperAdmin/User Management, feature settings, statutory controls, recycle bin, audit/history and remaining administrative modules are verified against the same Firebase owner contract and realtime event stream.

Only after all modules are verified should the project consider retiring SQL. No destructive SQL removal is performed in this phase package.

## Important preservation rules

- No Employee IDs are regenerated.
- No existing payroll/attendance calculation rules are changed.
- No existing screen layouts are redesigned.
- No Firebase records are blindly deleted or overwritten as part of this package.
- No second authoritative Android database is introduced.
