# FirebaseSqliteSyncService AuditLog identity conflict fix

## Error
EF Core reported:

`The instance of entity type 'AuditLog' cannot be tracked because another instance with the same key value for {'LogID'} is already being tracked.`

The failure occurred during the final `SaveChangesAsync()` of `UpsertTableAsync()`.

## Fix
`AuditLog` is now handled like `LeaveRequest`: each Firebase record is processed with its own short-lived `AppDbContext`.

This prevents identity-map/store-generated-key propagation from colliding between multiple AuditLog instances in one EF context.

The normal table final SaveChanges path is also protected so an unexpected projection failure cannot terminate the Web host.

## Preserved
- Firebase SSOT architecture
- Existing database schema
- Existing payroll/attendance/leave business logic
- Existing UI and application flow
- Existing Firebase record keys
