#!/usr/bin/env python3
"""1200-T deterministic full regression/integration audit.

Static/deterministic validation only. This intentionally does not claim a
real Android/.NET build when the toolchain/dependencies are unavailable.
"""
from pathlib import Path
import json, re, sys, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
checks=[]

def ok(label, condition): checks.append((label, bool(condition)))
def text(path): return (ROOT/path).read_text(encoding='utf-8', errors='ignore')
def require(label,path,pattern): ok(label, re.search(pattern,text(path),re.I|re.M|re.S) is not None)

# 1. Previous stage audits must remain green.
for script in [
    '1200L_geofence_integration_audit.py','1200M_background_tracking_audit.py',
    '1200O_admin_dashboard_integration_audit.py','1200P_reports_export_parity_audit.py',
    '1200Q_audit_traceability_audit.py','1200R_security_role_scope_audit.py',
    '1200S_offline_reconnect_stress_test.py','employee_domain_regression_audit.py']:
    import subprocess
    r=subprocess.run([sys.executable, str(ROOT/script)], cwd=ROOT, capture_output=True, text=True)
    ok(f'Prior audit passes: {script}', r.returncode == 0)

# 2. Android source integrity: no duplicate top-level class declarations.
packages={}
for p in (ROOT/'Android/app/src/main').rglob('*.kt'):
    s=p.read_text(encoding='utf-8',errors='ignore')
    pm=re.search(r'^\s*package\s+([\w.]+)',s,re.M)
    if not pm: continue
    pkg=pm.group(1)
    # Only declarations at column zero are top-level. Ignore nested classes.
    for m in re.finditer(r'^(?:data\s+|sealed\s+|open\s+|abstract\s+|enum\s+|annotation\s+)?class\s+(\w+)',s,re.M):
        key=(pkg,m.group(1)); packages.setdefault(key,[]).append(p)
dups={k:v for k,v in packages.items() if len(v)>1}
ok('Android has no duplicate top-level class declarations', not dups)
ok('Canonical FirebaseSyncManager exists', (ROOT/'Android/app/src/main/java/com/biometric/app/sync/FirebaseSyncManager.kt').exists())
ok('Stale duplicate FirebaseSyncManager removed', not (ROOT/'Android/app/src/main/FirebaseSyncManager.kt').exists())

# 3. Offline/reconnect contract remains wired.
require('Offline worker is Hilt worker','Android/app/src/main/java/com/biometric/app/domain/location/OfflineSyncWorker.kt',r'@HiltWorker')
require('Offline worker has unique work','Android/app/src/main/java/com/biometric/app/domain/location/OfflineSyncWorker.kt',r'enqueueUniqueWork\(\s*"offline_location_sync"')
require('Offline worker has retry backoff','Android/app/src/main/java/com/biometric/app/domain/location/OfflineSyncWorker.kt',r'setBackoffCriteria\(')
require('GPS sync uses stable client event ID','Android/app/src/main/java/com/biometric/app/domain/location/OfflineSyncWorker.kt',r'clientEventId\s*=\s*loc\.clientEventId')
require('Session end deferred until GPS drain','Android/app/src/main/java/com/biometric/app/domain/location/OfflineSyncWorker.kt',r'if \(!failed && deferredEndEvents\.isNotEmpty\(\)\)')

# 4. Firebase security/owner boundaries.
rules=json.loads(text('Android/database.rules.json'))
ok('Firebase database rules parse', True)
ce=rules['rules']['client_events']['$employeeId']
ok('client_events write is owner scoped', 'owner_uid' in ce['.write'] and 'ownerUid' in ce['.write'])
ok('client_events validation is owner scoped', 'owner_uid' in ce['$eventId']['.validate'] and 'ownerUid' in ce['$eventId']['.validate'])
require('Mobile realtime endpoint is Admin/SuperAdmin only','Web/Payroll.Web/Controllers/MobileRealtimeController.cs',r'Roles = "Admin,SuperAdmin"')
require('Diagnostics endpoint is Admin/SuperAdmin only','Web/Payroll.Web/Controllers/EmployeeLocationController.cs',r'Roles = "Admin,SuperAdmin".*?DiagnosticsController')

# 5. Core application registration and Firebase services.
require('Firebase realtime service registered','Web/Payroll.Web/Program.cs',r'AddSingleton<FirebaseRealtimeService>')
require('Mobile bearer authentication registered','Web/Payroll.Web/Program.cs',r'AddScheme<AuthenticationSchemeOptions, MobileTokenAuthenticationHandler>')
require('SignalR registered','Web/Payroll.Web/Program.cs',r'AddSignalR\(')
require('Android uses Firebase Auth','Android/app/build.gradle.kts',r'firebase\.auth')
require('Android uses WorkManager','Android/app/build.gradle.kts',r'work\.runtime\.ktx')

# 6. Manifest/XML parse and required workers/receiver declarations.
manifest=ROOT/'Android/app/src/main/AndroidManifest.xml'
try: ET.parse(manifest); xml_ok=True
except Exception: xml_ok=False
ok('AndroidManifest.xml parses', xml_ok)
ms=text('Android/app/src/main/AndroidManifest.xml')
for name in ['TrackingBootReceiver','OfflineSyncWorker']:
    # Worker normally need not be manually declared; receiver must be.
    if name == 'TrackingBootReceiver': ok('Tracking boot receiver declared', name in ms)

# 7. Python audit scripts compile.
import py_compile
for p in ROOT.glob('*audit*.py'):
    try:
        py_compile.compile(str(p), doraise=True)
        good=True
    except Exception: good=False
    ok(f'Python audit compiles: {p.name}', good)

# 8. No unreviewed implementation placeholders in first-party source.
# The YearEndSummary taxable-salary placeholder is a known pre-existing tax-rule
# limitation and is explicitly documented by 1200-T rather than silently ignored.
source_files=list((ROOT/'Web').rglob('*.cs'))+list((ROOT/'Android/app/src/main').rglob('*.kt'))
implementation_markers=[]
for p in source_files:
    s=p.read_text(encoding='utf-8',errors='ignore')
    if re.search(r'NotImplementedException|TODO\s*:\s*IMPLEMENT|Coming Soon',s,re.I):
        implementation_markers.append(str(p))
ok('No unimplemented-code markers in first-party C#/Kotlin', not implementation_markers)
year_end=text('Web/Payroll.Web/Services/YearEndSummaryService.cs')
ok('Known year-end taxable basis is explicitly marked for tax-rule refinement', 'grossTaxableSalaryPlaceholder' in year_end and 'needs to be refined based on tax rules' in year_end)

# 9. Required final-stage documentation exists.
ok('1200-S result documentation exists', (ROOT/'docs_1200S_RESULT.md').exists())
ok('1200-R result documentation exists', (ROOT/'docs_1200R_RESULT.md').exists())

failed=[n for n,x in checks if not x]
print(f'1200-T Full Regression/Integration static audit: {len(checks)-len(failed)}/{len(checks)} checks passed')
for n,x in checks: print(('PASS' if x else 'FAIL')+' | '+n)
if failed:
    print('\nFAILED:')
    for n in failed: print(' - '+n)
    sys.exit(1)
