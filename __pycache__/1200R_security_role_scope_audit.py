#!/usr/bin/env python3
from pathlib import Path
import re, json
ROOT=Path(__file__).resolve().parent
checks=[]
def req(label,path,pattern):
    s=(ROOT/path).read_text(encoding='utf-8',errors='ignore')
    checks.append((label,re.search(pattern,s,re.I|re.M|re.S) is not None))
def forbid(label,path,pattern):
    s=(ROOT/path).read_text(encoding='utf-8',errors='ignore')
    checks.append((label,re.search(pattern,s,re.I|re.M|re.S) is None))
req('Mobile realtime restricted to Admin/SuperAdmin','Web/Payroll.Web/Controllers/MobileRealtimeController.cs',r'Authorize\(AuthenticationSchemes = "MobileBearer", Roles = "Admin,SuperAdmin"\)')
req('Employee GPS endpoint has explicit application roles','Web/Payroll.Web/Controllers/EmployeeLocationController.cs',r'Authorize\(Roles = "Employee,Admin,SuperAdmin"\)')
req('Employee GPS enforces claimed employee scope','Web/Payroll.Web/Controllers/EmployeeLocationController.cs',r'User\.IsInRole\("Employee"\).*?claimedEmployeeId.*?claimedEmployeeId != request\.EmployeeId')
req('Diagnostics endpoint restricted to Admin/SuperAdmin','Web/Payroll.Web/Controllers/EmployeeLocationController.cs',r'Authorize\(Roles = "Admin,SuperAdmin"\).*?DiagnosticsController')
rules=json.loads((ROOT/'Android/database.rules.json').read_text())['rules']
ce=rules['client_events']
reqw=ce['$employeeId']['.write']
reqv=ce['$employeeId']['$eventId']['.validate']
checks.append(('client_events write requires owner_uid', 'auth.token.owner_uid != null' in reqw and 'newData.child(\'ownerUid\').val() == auth.token.owner_uid' in reqw))
checks.append(('client_events validates owner_uid', "newData.child('ownerUid').val() == auth.token.owner_uid" in reqv))
f='Android/app/src/main/java/com/biometric/app/sync/FirebaseSyncManager.kt'
req(f'Firebase client events include ownerUid ({f})',f,r'"ownerUid" to ownerUid')
failed=[n for n,x in checks if not x]
print(f'1200-R Security/Role/Scope static audit: {len(checks)-len(failed)}/{len(checks)} checks passed')
for n,x in checks: print(('PASS' if x else 'FAIL')+' | '+n)
if failed: raise SystemExit(1)
