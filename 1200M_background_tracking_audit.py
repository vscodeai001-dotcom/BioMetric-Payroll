from pathlib import Path

ROOT = Path(__file__).resolve().parent
checks = {
    'TrackingService durable queue': 'locationDao.insert(local)' in (ROOT/'Android/app/src/main/java/com/biometric/app/domain/location/TrackingService.kt').read_text(),
    'Immediate WorkManager sync': 'syncManager.scheduleImmediateSync()' in (ROOT/'Android/app/src/main/java/com/biometric/app/domain/location/TrackingService.kt').read_text(),
    'Lifecycle START queue': 'OfflineTrackingEvent.SESSION_STARTED' in (ROOT/'Android/app/src/main/java/com/biometric/app/domain/location/TrackingService.kt').read_text(),
    'Lifecycle END queue': 'OfflineTrackingEvent.SESSION_ENDED' in (ROOT/'Android/app/src/main/java/com/biometric/app/domain/location/TrackingService.kt').read_text(),
    'Server lifecycle bridge': 'ProcessFirebaseTrackingLifecycleEventAsync' in (ROOT/'Web/Payroll.Web/Services/FirebaseSqliteSyncService.cs').read_text(),
    'Existing attendance authority': 'GeoLocationService' in (ROOT/'Web/Payroll.Web/Services/FirebaseSqliteSyncService.cs').read_text(),
}
for name, value in checks.items():
    print(f'{name}: {"PASS" if value else "FAIL"}')
if not all(checks.values()): raise SystemExit(1)
