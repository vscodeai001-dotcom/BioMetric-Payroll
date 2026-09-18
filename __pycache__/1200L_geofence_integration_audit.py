#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
checks = []

def require(label, path, pattern):
    text = (ROOT / path).read_text(encoding='utf-8', errors='ignore')
    ok = re.search(pattern, text, re.I | re.M | re.S) is not None
    checks.append((label, ok))

def forbid(label, path, pattern):
    text = (ROOT / path).read_text(encoding='utf-8', errors='ignore')
    ok = re.search(pattern, text, re.I | re.M | re.S) is None
    checks.append((label, ok))

firebase = 'Web/Payroll.Web/Services/FirebaseSqliteSyncService.cs'
geo = 'Web/Payroll.Web/Services/GeoLocationService.cs'
mobile = 'Web/Payroll.Web/Controllers/MobileEmployeeController.cs'
android = 'Android/app/src/main/java/com/biometric/app/sync/FirebaseSyncManager.kt'

require('Firebase live GPS enters authoritative GeoLocationService', firebase,
        r'ProcessFirebaseLiveLocationAsync.*?UpdateGpsSessionAsync')
require('Firebase live GPS recalculates Admin geofence server-side', firebase,
        r'GetDistanceFromOfficeAsync\(\s*latitude,\s*longitude\)')
require('Firebase live GPS validates coordinate bounds', firebase,
        r'latitude.*-90.*90.*longitude.*-180.*180')
require('GeoLocationService performs automatic geofence reconciliation', geo,
        r'ProcessAutomaticGeofencePunchAsync')
require('Mobile GPS endpoint uses server-calculated geofence state', mobile,
        r'GetDistanceFromOfficeAsync\(request\.Latitude,\s*request\.Longitude\)')
require('Android GPS history is immutably keyed by client event', android,
        r'tracking/history/\$employeeId/\$clientEventId')
require('Android GPS live state is owner-scoped', android,
        r'owners/\$ownerUid/tracking/live/\$employeeId')
forbid('Firebase live handler does not directly compete with LiveLocationStore writer', firebase,
       r'ProcessFirebaseLiveLocationAsync.*?LiveLocationStore\.Update\(')

failed = [name for name, ok in checks if not ok]
print(f'1200-L static geofence integration audit: {len(checks)-len(failed)}/{len(checks)} checks passed')
for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
if failed:
    raise SystemExit(1)
