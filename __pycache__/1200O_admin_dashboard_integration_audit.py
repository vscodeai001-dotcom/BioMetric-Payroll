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

service = 'Web/Payroll.Web/Services/FirebaseAdminDashboardService.cs'
realtime = 'Web/Payroll.Web/Services/FirebaseRealtimeService.cs'
home = 'Web/Payroll.Web/Components/Pages/Admin/Home.razor'
main = 'Android/app/src/main/java/com/biometric/app/ui/MainActivity.kt'
signalr = 'Android/app/src/main/java/com/biometric/app/sync/SignalRManager.kt'
app = 'Android/app/src/main/java/com/biometric/app/BiometricApplication.kt'

require('Admin dashboard aggregates owner-scoped Firebase tables', service,
        r'GetOwnerTableAsync\(ownerUid, "employees".*?GetOwnerTableAsync\(ownerUid, "daily_summaries"')
require('Admin dashboard tracking is owner-scoped', service,
        r'GetOwnerTrackingLiveAsync\(ownerUid')
forbid('Admin dashboard no longer reads global tracking for KPI aggregation', service,
       r'GetGlobalRecordAsync\("tracking/live"')
require('Owner tracking reader exists', realtime,
        r'GetOwnerTrackingLiveAsync')
require('Web dashboard has Firebase owner realtime stream', home,
        r'StreamOwnerChangesAsync')
require('Web dashboard refreshes aggregate on tracking stream', home,
        r'StreamTrackingChangesAsync.*?LoadAdminDashboard')
require('Android admin map consumes Firebase-backed live location facade', signalr,
        r'firebaseSync\.getGlobalRef\(\).*?tracking.*?live')
require('Android main dashboard renders Firebase-synchronized KPIs', main,
        r'viewModel\.globalStats\.collectLatest')
require('Application-scoped realtime coordinator is started', app,
        r'adminRealtimeCoordinator\.start')

failed = [name for name, ok in checks if not ok]
print(f'1200-O static Admin Dashboard integration audit: {len(checks)-len(failed)}/{len(checks)} checks passed')
for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
if failed:
    raise SystemExit(1)
