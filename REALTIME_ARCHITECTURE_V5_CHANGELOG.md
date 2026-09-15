# Realtime Architecture v5

- Added optional, allowlisted Neon -> Firebase read-model bootstrap.
- Added row-level Neon -> Firebase replication after successful EF Core SaveChanges.
- Replication exports scalar fields only and excludes GPS/session/device/theme/notification/audit-sensitive high-frequency tables.
- Neon remains the authoritative business database; Firebase is a realtime read model/transport.
- Existing SignalR, UI, layout, payroll rules, and database schema are unchanged.
- Bootstrap is disabled by default. Set `FIREBASE_NEON_BOOTSTRAP=true` and `FIREBASE_OWNER_UID` to perform an initial export.
- Client writes to replicated `owners/{ownerUid}/data` are blocked.
